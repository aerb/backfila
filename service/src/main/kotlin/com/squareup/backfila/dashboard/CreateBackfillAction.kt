package com.squareup.backfila.dashboard

import com.squareup.backfila.client.BackfilaClientServiceClientProvider
import com.squareup.backfila.service.BackfilaDb
import com.squareup.backfila.service.DbBackfillRun
import com.squareup.backfila.service.DbRunInstance
import com.squareup.backfila.service.RegisteredBackfillQuery
import com.squareup.backfila.service.ServiceQuery
import com.squareup.protos.backfila.clientservice.PrepareBackfillRequest
import misk.MiskCaller
import misk.exceptions.BadRequestException
import misk.hibernate.Query
import misk.hibernate.Transacter
import misk.hibernate.newQuery
import misk.logging.getLogger
import misk.scope.ActionScoped
import misk.security.authz.Authenticated
import misk.web.PathParam
import misk.web.Post
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import misk.web.toResponseBody
import okhttp3.Headers
import java.net.HttpURLConnection
import javax.inject.Inject

data class CreateBackfillRequest(
  val backfill_name: String,
  // TODO move defaults to UI
  val scan_size: Long = 1000,
  val batch_size: Long = 10000,
  val num_threads: Long = 5,
  val pkey_range_start: String? = null,
  val pkey_range_end: String? = null,
  // Parameters that go to the client service.
  val parameter_map: Map<String, String> = mapOf()
)

class CreateBackfillAction @Inject constructor(
  private val caller: @JvmSuppressWildcards ActionScoped<MiskCaller?>,
  @BackfilaDb private val transacter: Transacter,
  private val queryFactory: Query.Factory,
  private val clientProvider: BackfilaClientServiceClientProvider
) : WebAction {

  @Post("/{service}/create")
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  // TODO allow any user
  @Authenticated(roles = ["eng"])
  fun create(
    @PathParam service: String,
    @RequestBody request: CreateBackfillRequest
  ): Response<ResponseBody> {
    // TODO check user has permissions for this service with `X-Forwarded-All-Capabilities` header

    logger.info { "Create backfill for $service by ${caller.get()?.user}" }

    val (serviceId, serviceType) = transacter.transaction { session ->
      val dbService = queryFactory.newQuery<ServiceQuery>()
          .registryName(service)
          .uniqueResult(session) ?: throw BadRequestException("`$service` doesn't exist")
      Pair(dbService.id, dbService.service_type)
    }

    val registeredBackfillId = transacter.transaction { session ->
      val registeredBackfill = queryFactory.newQuery<RegisteredBackfillQuery>()
          .serviceId(serviceId)
          .name(request.backfill_name)
          .active()
          .uniqueResult(session)
          ?: throw BadRequestException("`${request.backfill_name}` doesn't exist")
      logger.info {
        "Found registered backfill for `$service`::`${request.backfill_name}`" +
            " [id=${registeredBackfill.id}]"
      }
      registeredBackfill.id
    }

    val client = clientProvider.clientFor(service, serviceType)
    val prepareBackfillResponse =
        client.prepareBackfill(PrepareBackfillRequest(request.backfill_name, null, listOf()))
    // TODO check for error and fail
    val instances = prepareBackfillResponse.instances
    if (instances.distinctBy { it.instance_name }.size != instances.size) {
      throw BadRequestException("PrepareBackfill did not return distinct instance names:" +
          " ${instances.map { it.instance_name }}")
    }
    // TODO validate params fit names

    // TODO validate backfill_ranges

    val backfillRunId = transacter.transaction { session ->
      val backfillRun = DbBackfillRun(
          serviceId,
          registeredBackfillId,
          request.parameter_map,
          caller.get()?.user,
          request.scan_size,
          request.batch_size,
          request.num_threads
      )
      session.save(backfillRun)

      for (instance in instances) {
        val dbRunInstance = DbRunInstance(
            backfillRun.id, instance.instance_name, instance.backfill_range)
        session.save(dbRunInstance)
      }

      backfillRun.id
    }

    return Response(
        body = "go to /backfills/$backfillRunId".toResponseBody(),
        statusCode = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = Headers.of("Location", "/backfills/$backfillRunId"))
  }

  companion object {
    private val logger = getLogger<CreateBackfillAction>()
  }
}