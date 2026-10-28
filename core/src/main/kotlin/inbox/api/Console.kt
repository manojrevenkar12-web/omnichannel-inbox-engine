package inbox.api

import inbox.domain.Author
import inbox.domain.Channel
import inbox.service.Assign
import inbox.service.Auth
import inbox.service.Caller
import inbox.service.EventProcessor
import inbox.service.Send
import inbox.service.SendRequest
import inbox.service.SendResult
import inbox.service.Sender
import inbox.service.Views
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

data class SendBody(val text: String? = null, val template: String? = null, val language: String? = null, val channel: String? = null)

data class AssignBody(val teammateId: String? = null)

/** The API the inbox console uses. Every call is scoped to the caller's workspace. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
class Console(
    private val auth: Auth,
    private val views: Views,
    private val send: Send,
    private val assign: Assign,
    private val events: EventProcessor,
    private val sender: Sender,
) {
    private fun caller(authorization: String?): Caller =
        auth.caller(authorization)
            ?: throw WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).entity(mapOf("error" to "unknown or missing token")).type(MediaType.APPLICATION_JSON).build())

    private fun notFound(what: String): Response = Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "no such $what")).build()

    @GET
    @Path("/me")
    fun me(
        @HeaderParam("Authorization") authorization: String?,
    ): Caller = caller(authorization)

    @GET
    @Path("/conversations")
    fun conversations(
        @HeaderParam("Authorization") authorization: String?,
        @QueryParam("view") view: String?,
    ): Response {
        val me = caller(authorization)
        return Response.ok(views.list(me.workspaceId, view ?: "all", me.teammateId)).build()
    }

    @GET
    @Path("/conversations/{id}")
    fun conversation(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: String,
    ): Response {
        val me = caller(authorization)
        return views.detail(me.workspaceId, id, me.teammateId)?.let { Response.ok(it).build() } ?: notFound("conversation")
    }

    @POST
    @Path("/conversations/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    fun sendMessage(
        @HeaderParam("Authorization") authorization: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: String,
        body: SendBody,
    ): Response {
        val me = caller(authorization)
        val channel =
            body.channel?.let { runCatching { Channel.valueOf(it.uppercase()) }.getOrNull() ?: return Response.status(400).entity(mapOf("error" to "unknown channel $it")).build() }
        val r = send.submit(me.workspaceId, id, SendRequest(body.text, body.template, body.language, channel), Author.HUMAN, me.teammateId, idempotencyKey?.takeIf { it.isNotBlank() })
        return when (r) {
            is SendResult.Queued -> Response.status(if (r.replayed) 200 else 202).entity(r).build()
            is SendResult.TemplateRequired -> Response.status(409).entity(mapOf("error" to "template_required", "reason" to r.reason, "closedAt" to r.closedAt)).build()
            is SendResult.Refused -> Response.status(409).entity(mapOf("error" to "refused", "reason" to r.reason)).build()
            is SendResult.NotFound -> notFound(r.what)
        }
    }

    @POST
    @Path("/conversations/{id}/assign")
    @Consumes(MediaType.APPLICATION_JSON)
    fun assignTo(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: String,
        body: AssignBody,
    ): Response {
        val me = caller(authorization)
        return if (assign.assignTo(me.workspaceId, id, body.teammateId ?: me.teammateId)) Response.noContent().build() else notFound("open conversation or teammate")
    }

    @POST
    @Path("/conversations/{id}/handback")
    fun handBack(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: String,
    ): Response {
        val me = caller(authorization)
        return if (assign.handBackToAi(me.workspaceId, id)) Response.noContent().build() else notFound("open conversation")
    }

    @POST
    @Path("/conversations/{id}/resolve")
    fun resolve(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: String,
    ): Response {
        val me = caller(authorization)
        return if (assign.resolve(me.workspaceId, id)) Response.noContent().build() else notFound("open conversation")
    }

    @GET
    @Path("/team")
    fun team(
        @HeaderParam("Authorization") authorization: String?,
    ): Response = Response.ok(views.team(caller(authorization).workspaceId)).build()

    @GET
    @Path("/templates")
    fun templates(
        @HeaderParam("Authorization") authorization: String?,
    ): Response = Response.ok(views.templates(caller(authorization).workspaceId)).build()

    @GET
    @Path("/metrics")
    fun metrics(
        @HeaderParam("Authorization") authorization: String?,
    ): Response {
        val me = caller(authorization)
        return Response.ok(mapOf("inbox" to views.metrics(me.workspaceId), "events" to events.backlog(), "outbox" to sender.backlog())).build()
    }
}
