package me.nomen.synapse.pdv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import me.nomen.synapse.data.SynapseJson
import me.nomen.synapse.data.parseObjeto

/**
 * SQLite do framework Android (sem dependências extras), em armazenamento privado do app.
 * Observação de segurança: o arquivo não é cifrado (o painel web também não cifra o IndexedDB); a fila contém
 * dados de venda e identificação de clientes. Cifrar o banco (SQLCipher) é uma decisão de produto pendente.
 */
class SqlitePdvStore(context: Context) : SQLiteOpenHelper(context, "synapse_pdv.db", null, 1), PdvStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fila(op_id TEXT PRIMARY KEY, criado_em TEXT NOT NULL, status TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE config(chave TEXT PRIMARY KEY, json TEXT NOT NULL)")
    }

    // WAL: gravações atômicas e resistentes a queda de energia/fechamento do app no meio de uma escrita.
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {}

    override suspend fun salvarOp(op: OperacaoFila) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO fila(op_id, criado_em, status, json) VALUES(?,?,?,?)",
            arrayOf<Any>(op.opId, op.criadoEm, op.status, op.toJson().toString())
        )
    }

    override suspend fun obterOp(opId: String): OperacaoFila? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT json FROM fila WHERE op_id = ?", arrayOf(opId)).use { c ->
            if (c.moveToFirst()) OperacaoFila.deJson(parseObjeto(c.getString(0))) else null
        }
    }

    override suspend fun todasOps(): List<OperacaoFila> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT json FROM fila ORDER BY criado_em", null).use { c ->
            buildList { while (c.moveToNext()) OperacaoFila.deJson(parseObjeto(c.getString(0)))?.let { add(it) } }
        }
    }

    override suspend fun salvarConfig(chave: String, valor: JsonObject) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO config(chave, json) VALUES(?,?)", arrayOf<Any>(chave, valor.toString()))
    }

    override suspend fun obterConfig(chave: String): JsonObject? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT json FROM config WHERE chave = ?", arrayOf(chave)).use { c ->
            if (c.moveToFirst()) SynapseJson.parseToJsonElement(c.getString(0)) as? JsonObject else null
        }
    }

    override suspend fun limparTudo() = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("DELETE FROM fila"); writableDatabase.execSQL("DELETE FROM config")
    }
}
