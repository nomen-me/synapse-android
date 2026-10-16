package me.nomen.synapse.pdv
import android.content.Context
import kotlinx.serialization.json.JsonObject
// Stub do SqlitePdvStore real (usa SQLiteOpenHelper).
class SqlitePdvStore(context: Context) : PdvStore {
    override suspend fun salvarOp(op: OperacaoFila) {}
    override suspend fun obterOp(opId: String): OperacaoFila? = null
    override suspend fun todasOps(): List<OperacaoFila> = emptyList()
    override suspend fun salvarConfig(chave: String, valor: JsonObject) {}
    override suspend fun obterConfig(chave: String): JsonObject? = null
    override suspend fun limparTudo() {}
}
