package bot

import com.timcastelijns.chatexchange.chat.*
import data.commands.*
import data.repositories.StarredMessage
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject

class Bot(
        private val getUserStatsCommand: GetUserStatsCommand,
        private val syncStarsDataCommand: SyncStarsDataCommand,
        private val getStarsDataCommand: GetStarsDataCommand
) {

    private val aliveSubject = BehaviorSubject.create<Boolean>()

    lateinit var room: Room

    private val disposables = CompositeDisposable()

    fun observeLife(): Observable<Boolean> {
        return aliveSubject.hide()
    }

    fun boot(client: StackExchangeClient, roomId: Int) {
        aliveSubject.onNext(true)
        joinRoom(client, roomId)
    }

    private fun die(killer: User) {
        disposables.clear()

        aliveSubject.onNext(false)
        aliveSubject.onComplete()

        println("Died. Killed by ${killer.name}")
    }

    private fun joinRoom(client: StackExchangeClient, roomId: Int) {
        room = client.joinRoom(ChatHost.STACK_OVERFLOW, roomId)
        println("Joined room #$roomId")
    }

    fun start() {
        room.accessLevelChangedEventListener = {
            if (it.accessLevel == AccessLevel.REQUEST) {
                processUserRequestedAccess(it.targetUser)
            }
        }

        room.messagePostedEventListener = {
            println("${it.userName}: ${it.message.content}")

            if (it.message.content!!.startsWith("!")) {
                processMessage(it.message)
            }
        }
    }

    private fun processUserRequestedAccess(user: User) {
        disposables.add(getUserStatsCommand.execute(user)
                .subscribe { it ->
                    room.send("${user.name} requested access. $it")
                })
    }

    private fun processMessage(message: Message) {
        val command = message.content!!.substring(1)
        if (command == "shoo" && message.user!!.id == 1843331L) {
            die(killer = message.user!!)
        } else if (command.startsWith("stats")) {
            val userId = command.split(" ").last().toLongOrNull()

            val user = if (userId == null) {
                message.user!!
            } else {
                room.getUser(userId)
            }

            processShowStatsCommand(user)
        } else if (command.startsWith("sync stars")) {
            processSyncStarsCommand()
        } else if (command.startsWith("stars")) {
            val username = if (command.split(" ").size > 1) {
                command.split(" ").last()
            } else null

            processShowStarsCommand(username)
        }
    }

    private fun processShowStatsCommand(user: User) {
        disposables.add(getUserStatsCommand.execute(user)
                .subscribe { it ->
                    room.send("Stats for ${user.name} -- $it")
                })
    }

    private fun processSyncStarsCommand() {
        disposables.add(syncStarsDataCommand.execute()
                .subscribe {
                    room.send("Done.")
                })
    }

    private fun processShowStarsCommand(username: String?) {
        disposables.add(getStarsDataCommand.execute(username)
                .subscribe { data ->
                    println(data.asTableString())
                    room.send(data.asTableString())
                })
    }

    private fun StarsData.asTableString(): String {
        if (starredMessages.isEmpty()) {
            return "No starred messages found"
        }

        val nameColumnMaxLength = 10
        val messageColumnMaxLength = 48

        val longestNameLength = starredMessages.maxBy { it.username.length }!!.username.length
        val nameColumnLength = if (longestNameLength >= nameColumnMaxLength) {
            nameColumnMaxLength
        } else {
            longestNameLength
        }

        val userHeader = "User".padEnd(nameColumnLength)
        val messageHeader = "Message ($totalStarredMessages)".padEnd(messageColumnMaxLength)
        val starsHeader = "Stars ($totalStars)"

        val header = " $userHeader | $messageHeader | $starsHeader | Link"
        val separator = "-".repeat(header.length)

        val table = mutableListOf<String>()
        table.add(header)
        table.add(separator)

        starredMessages.forEach {
            val user = it.username.truncate(nameColumnLength).padEnd(nameColumnLength)
            val message = it.message.truncate(messageColumnMaxLength).padEnd(messageColumnMaxLength)
            val stars = it.stars.toString().truncate(starsHeader.length).padEnd(starsHeader.length)
            val permanentLink = ""
            val line = " $user | $message | $stars | $permanentLink"
            table.add(line)
        }

        return table.joinToString("\n") { "    $it" }
    }
}
