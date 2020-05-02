package com.example.myapplication.base.mvi

import com.example.myapplication.base.mvi.command.*
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject

abstract class Store<Event, State, Action>(
	private val foregroundScheduler: Scheduler,
	private val backgroundScheduler: Scheduler
) {

	private val commands = PublishSubject.create<Command>()
	private val states = BehaviorSubject.create<State>()
	private val actions = PublishSubject.create<Action>()

	private val lifeCycleSubscriptions = CompositeDisposable()
	private val processCommandsSubscriptions = CompositeDisposable()

	private var attached = false

	open val initialState: State = TODO("Not used")
	open val bootstrapCommands = emptyList<Command>()
	open val statefulMiddlewares = emptyList<StatefulMiddleware<*, State>>()
	open val middlewares = emptyList<Middleware<*>>()

	init {
		try {
			states.onNext(initialState)
		} catch (ignored: NotImplementedError) {
		}
	}

	fun dispatchEvent(event: Event) {
		dispatchEventSource(Observable.just(event))
	}

	fun dispatchEventSource(eventSource: Observable<Event>) {
		if (attached) {
			lifeCycleSubscriptions.add(
				eventSource.observeOn(foregroundScheduler).subscribe { event ->
					try {
						val command = convertEvent(event)
						commands.onNext(command)
					} catch (ignored: NotImplementedError) {
					}
				}
			)
		}
	}

	fun attach(view: MviView<State, Action>) {
		launch()
		lifeCycleSubscriptions.add(
			states.observeOn(foregroundScheduler).subscribe { view.render(it) }
		)
		lifeCycleSubscriptions.add(
			actions.observeOn(foregroundScheduler).subscribe { view.processAction(it) }
		)
		attached = true
	}

	fun detach() {
		lifeCycleSubscriptions.dispose()
		attached = false
		finish()
	}

	fun launch() {
		val bootstrapCommandsSource = Observable.fromIterable(bootstrapCommands)
		val commandSource = commands.let { bootstrapCommandsSource.mergeWith(it) ?: it }
			.subscribeOn(backgroundScheduler)
			.replay(1)
			.refCount()

		processCommandsSubscriptions.add(
			commandSource.subscribe { command ->
				produceAction(command)?.let { actions.onNext(it) }
			}
		)

		val commandResultSource = Observable.merge(
			executeCommands(commandSource, states),
			commandSource.ofType(CommandCommandResult::class.java)
		)
			.replay(1)
			.refCount()

		processCommandsSubscriptions.add(
			commandResultSource.subscribe { commandResult ->
				produceCommand(commandResult)?.let { commands.onNext(it) }
			}
		)

		val initialState = states.value!!
		processCommandsSubscriptions.add(
			commandResultSource.scan(initialState, { state, commandResult -> reduceCommandResult(state, commandResult) })
				.distinctUntilChanged()
				.subscribe { states.onNext(it) }
		)
	}

	fun finish() {
		processCommandsSubscriptions.dispose()
	}

	open fun convertEvent(event: Event): Command = TODO("Not used")
	open fun produceAction(command: Command): Action? = null
	open fun produceCommand(commandResult: CommandResult): Command? = null
	open fun reduceCommandResult(state: State, commandResult: CommandResult): State = state

	private fun executeCommands(commands: Observable<Command>, state: Observable<State>): Observable<CommandResult> =
		commands.publish { commandSource ->
			statefulMiddlewares.forEach { it.attachState(state) }
			Observable.merge(mutableListOf<Observable<CommandResult>>().apply {
				addAll(statefulMiddlewares.map { commandSource.compose(it) })
				addAll(middlewares.map { commandSource.compose(it) })
			})
		}
}
