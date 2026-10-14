package me.nomen.synapse.data

import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cliente do Harmonia (n8n): `POST {harmonia}/webhook/<slug>`. Equivalente ao objeto `Harmonia`
 * do painel. NUNCA repete uma chamada automaticamente (muitos webhooks têm efeito colateral:
 * enviar mensagem, emitir nota, criar cobrança). A decisão de repetir é sempre do usuário.
 */
class HarmoniaClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun chamar(slug: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val req = Request.Builder()
            .url(urlDe(baseUrl(), "webhook/$slug"))
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(json))
            .build()
        return processar(http.executar(req))
    }

    /** Multipart (anexos). O boundary é calculado pelo OkHttp; o Content-Type nunca é forçado à mão. */
    suspend fun chamarComArquivo(
        slug: String, campos: Map<String, String>, campoArquivo: String,
        nomeArquivo: String, tipo: String, bytes: ByteArray
    ): JsonObject {
        val corpo = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            campos.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(campoArquivo, nomeArquivo, bytes.toRequestBody(tipo.toMediaType()))
        }.build()
        val req = Request.Builder().url(urlDe(baseUrl(), "webhook/$slug")).header("Accept", "application/json").post(corpo).build()
        return processar(http.executar(req))
    }

    private fun processar(r: RespostaHttp): JsonObject {
        val obj = parseObjeto(r.body)
        if (r.code !in 200..299) throw ApiException(r.code, mensagemDeErro(obj) ?: "Falha ao acionar o Harmonia (HTTP ${r.code}).")
        return obj
    }
}
