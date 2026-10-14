package me.nomen.synapse.pdv

import kotlinx.serialization.json.JsonObject

/** Armazenamento local do PDV (equivalente às stores `fila` e `config` do IndexedDB do painel). */
interface PdvStore {
    suspend fun salvarOp(op: OperacaoFila)
    suspend fun obterOp(opId: String): OperacaoFila?
    suspend fun todasOps(): List<OperacaoFila>
    suspend fun salvarConfig(chave: String, valor: JsonObject)
    suspend fun obterConfig(chave: String): JsonObject?
    suspend fun limparTudo()
}
