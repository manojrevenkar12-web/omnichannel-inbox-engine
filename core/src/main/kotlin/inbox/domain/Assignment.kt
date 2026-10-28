package inbox.domain

/** A person who answers conversations. */
data class Teammate(val id: String, val name: String, val online: Boolean, val capacity: Int, val openConversations: Int, val lastAssignedSeq: Long)

/**
 * Who gets a conversation the AI handed over: the online teammate with free capacity and the
 * fewest open conversations, ties broken by who was assigned longest ago (round robin). Nobody
 * available means the conversation waits in the unassigned queue, which is visible to everyone;
 * it is never assigned to someone offline.
 */
object Assignment {
    fun choose(team: List<Teammate>): Teammate? =
        team
            .filter { it.online && it.openConversations < it.capacity }
            .minWithOrNull(compareBy<Teammate>({ it.openConversations }, { it.lastAssignedSeq }, { it.id }))
}
