package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nomen.synapse.data.ConfiguracaoException
import me.nomen.synapse.data.RitmoClient
import me.nomen.synapse.data.lista
import me.nomen.synapse.data.objeto
import me.nomen.synapse.data.str
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class PdvConfigRepository(private val ritmo: RitmoClient, private val store: PdvStore) {

    /**
     * pdvResolverDeposito do painel: usa o cache local; só refaz online quando o cache é anterior à chave
     * `conta_acrescimos`; offline devolve o que houver. Premissa do painel: um único POS Profile ativo.
     */
    suspend fun resolverDeposito(online: Boolean): DepositoPdv? = DepositoPdv.deJson(resolverDepositoJson(online))

    private suspend fun resolverDepositoJson(online: Boolean): JsonObject? {
        val cfg = store.obterConfig("deposito")
        if (cfg != null && cfg.str("warehouse") != null && ("conta_acrescimos" in cfg || !online)) return cfg
        if (!online) return cfg
        return try {
            val perfil = ritmo.listar("POS Profile", listOf("name", "warehouse", "company", "currency", "selling_price_list"), limite = 1)
                .lista("data").firstOrNull() as? JsonObject
            val nome = perfil?.str("name")
            val warehouse = perfil?.str("warehouse")
            if (perfil == null || nome == null || warehouse == null) return cfg
            var modo: String? = null
            var conta: String? = null
            try {
                val doc = ritmo.obter("POS Profile", nome).objeto("data")   // payments é child table: precisa do documento inteiro
                modo = (doc?.lista("payments")?.firstOrNull() as? JsonObject)?.str("mode_of_payment")
                conta = doc?.str("synapse_conta_acrescimos")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            val novo = buildJsonObject {
                put("chave", "deposito"); put("warehouse", warehouse); put("pos_profile", nome)
                put("company", perfil.str("company")); put("currency", perfil.str("currency"))
                put("selling_price_list", perfil.str("selling_price_list"))
                put("modo_pagamento", modo); put("conta_acrescimos", conta)
            }
            store.salvarConfig("deposito", novo)
            novo
        } catch (e: CancellationException) { throw e } catch (_: Exception) { cfg }
    }

    suspend fun autorizacaoLocal(): AutorizacaoCaixa? = AutorizacaoCaixa.deJson(store.obterConfig("autorizacao_caixa"))

    /** Baixa a lista de operadores autorizados + credenciais de PIN do POS Profile. Pacote incompleto NÃO sobrescreve o local. */
    suspend fun sincronizarAutorizacao(): AutorizacaoCaixa {
        val dep = resolverDeposito(online = true)
            ?: throw ConfiguracaoException("POS Profile não resolvido — não é possível sincronizar autorização do Caixa")
        val pacote = ritmo.sincronizarCaixa(dep.posProfile).objeto("message")
        val usuarios = pacote?.get("usuarios_autorizados") as? JsonArray
        val sincronizadoEm = pacote?.str("sincronizado_em")
        if (pacote == null || usuarios == null || sincronizadoEm == null) {
            throw ConfiguracaoException("Pacote de autorização do Caixa veio incompleto — mantendo o estado local anterior")
        }
        val json = buildJsonObject {
            put("chave", "autorizacao_caixa")
            put("pos_profile", pacote.str("pos_profile")); put("sincronizado_em", sincronizadoEm)
            put("versao", pacote["versao"] ?: JsonNull)
            put("restricao_configurada", (pacote["restricao_configurada"] as? JsonPrimitive)?.content == "true")
            put("usuarios", usuarios)
            put("sincronizado_localmente_em", Instant.now().toString())
        }
        store.salvarConfig("autorizacao_caixa", json)
        return AutorizacaoCaixa.deJson(json)!!
    }

    /** Define o PIN do próprio usuário (6 dígitos). O cálculo é local; o servidor só guarda hash/salt/iterações. */
    suspend fun definirPinOnline(pin: String, emailAtual: String) {
        if (!PinCrypto.pinValido(pin)) throw IllegalArgumentException("O PIN precisa ter exatamente 6 dígitos numéricos")
        val iteracoes = PinCrypto.calibrarIteracoes(300)
        val salt = PinCrypto.gerarSalt()
        val hash = PinCrypto.derivarHashHex(pin, salt, iteracoes)
        val saltB64 = PinCrypto.paraBase64(salt)
        ritmo.definirPin(hash, saltB64, iteracoes)
        // Atualiza a cópia local, se o usuário já consta na autorização sincronizada.
        val local = store.obterConfig("autorizacao_caixa") ?: return
        val lista = local["usuarios"] as? JsonArray ?: return
        val novaLista = JsonArray(lista.map { el ->
            val u = el as? JsonObject
            if (u != null && u.str("user_id") == emailAtual) {
                JsonObject(u.toMap() + mapOf<String, JsonElement>(
                    "pin_definido" to JsonPrimitive(true), "pin_hash" to JsonPrimitive(hash),
                    "pin_salt" to JsonPrimitive(saltB64), "pin_kdf_iteracoes" to JsonPrimitive(iteracoes)
                ))
            } else el
        })
        store.salvarConfig("autorizacao_caixa", JsonObject(local.toMap() + ("usuarios" to novaLista)))
    }
}
