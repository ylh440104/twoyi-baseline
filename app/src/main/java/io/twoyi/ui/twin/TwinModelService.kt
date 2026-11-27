/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.ui.twin

import android.content.Context
import io.twoyi.TwinModelCatalog
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the provider's model list.
 *
 * The rules (URL shape, status mapping, JSON reading) are in `TwinModelCatalog`
 * and covered by host tests; this class only does the network call and reports a
 * result the UI can render. Every failure resolves to an existing string so the
 * copy stays identical to the newest build.
 */
object TwinModelService {
    sealed class Result {
        class Ok(val models: List<String>) : Result()
        class Failed(val messageRes: Int, val detail: String?) : Result()
    }

    private const val TIMEOUT_MS = 15_000

    fun fetch(context: Context, baseUrl: String, apiKey: String): Result {
        val url = TwinModelCatalog.modelsUrl(baseUrl)
        if (url.isEmpty()) {
            return Result.Failed(io.twoyi.R.string.twin_base_url_invalid, null)
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            val body = readBody(connection, status)
            when (TwinModelCatalog.classify(status)) {
                TwinModelCatalog.Failure.NONE -> {
                    val models = TwinModelCatalog.parseModelIds(body)
                    if (models.isEmpty()) {
                        Result.Failed(io.twoyi.R.string.twin_parse_error, TwinModelCatalog.errorMessage(body))
                    } else {
                        Result.Ok(models)
                    }
                }
                TwinModelCatalog.Failure.BAD_KEY ->
                    Result.Failed(io.twoyi.R.string.twin_key_invalid, TwinModelCatalog.errorMessage(body))
                TwinModelCatalog.Failure.NOT_FOUND ->
                    Result.Failed(io.twoyi.R.string.twin_base_url_invalid, TwinModelCatalog.errorMessage(body))
                TwinModelCatalog.Failure.SERVER ->
                    Result.Failed(io.twoyi.R.string.twin_network_error, "HTTP $status")
                TwinModelCatalog.Failure.MALFORMED_BODY ->
                    Result.Failed(io.twoyi.R.string.twin_parse_error, null)
            }
        } catch (failure: Exception) {
            Result.Failed(io.twoyi.R.string.twin_network_error, failure.message)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}