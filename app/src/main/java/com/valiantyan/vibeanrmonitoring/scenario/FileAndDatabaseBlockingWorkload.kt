package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Demo 专用主线程慢 IO/DB 工作负载。
 *
 * 这个类故意在调用线程执行同步文件写入、fsync 和 SQLite 大事务，用于制造可观测的 ANR 现场。
 *
 * @param context 应用上下文，用于访问私有文件和数据库目录。
 * @param fileChunks 文件写入块数量。
 * @param fileChunkSizeBytes 每个文件块大小。
 * @param syncEveryChunks 每写入多少个文件块执行一次 fsync。
 * @param databaseRows SQLite 事务插入行数。
 * @param databasePayloadBytes 每行 SQLite payload 大小。
 */
class FileAndDatabaseBlockingWorkload(
    context: Context,
    private val fileChunks: Int = DEFAULT_FILE_CHUNKS,
    private val fileChunkSizeBytes: Int = DEFAULT_FILE_CHUNK_SIZE_BYTES,
    private val syncEveryChunks: Int = DEFAULT_SYNC_EVERY_CHUNKS,
    private val databaseRows: Int = DEFAULT_DATABASE_ROWS,
    private val databasePayloadBytes: Int = DEFAULT_DATABASE_PAYLOAD_BYTES,
) : MainThreadIoWorkload {
    // 使用应用上下文，避免场景类持有 Activity。
    private val appContext: Context = context.applicationContext

    /**
     * 依次执行同步文件写入和 SQLite 事务。此方法由按钮点击在主线程调用。
     */
    override fun runIoDatabaseFileWorkload(): Unit {
        val workingDir: File = File(appContext.filesDir, WORKING_DIR_NAME)
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }
        writeBlockingFile(file = File(workingDir, BLOCKING_FILE_NAME))
        runBlockingDatabaseTransaction()
    }

    // 通过频繁 fsync 放大真实磁盘同步成本，避免用 sleep 伪造 IO 栈。
    private fun writeBlockingFile(file: File): Unit {
        val buffer: ByteArray = ByteArray(fileChunkSizeBytes) { index: Int ->
            (index % BYTE_PATTERN_MOD).toByte()
        }
        FileOutputStream(file, false).use { stream: FileOutputStream ->
            repeat(fileChunks) { index: Int ->
                stream.write(buffer)
                if (index % syncEveryChunks == 0) {
                    stream.flush()
                    stream.fd.sync()
                }
            }
            stream.flush()
            stream.fd.sync()
        }
    }

    // SQLite 大事务用于提供数据库调用栈，和文件 IO 证据互相印证。
    private fun runBlockingDatabaseTransaction(): Unit {
        val databaseFile: File = appContext.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        val database: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS demo_blocking_io (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "label TEXT NOT NULL, " +
                    "payload BLOB NOT NULL" +
                    ")",
            )
            insertRowsInTransaction(database = database)
        } finally {
            database.close()
        }
    }

    // 把事务插入拆出，保持数据库打开/关闭流程清晰。
    private fun insertRowsInTransaction(database: SQLiteDatabase): Unit {
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM demo_blocking_io")
            repeat(databaseRows) { index: Int ->
                insertPayloadRow(
                    database = database,
                    index = index,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    // 每行 payload 使用不同字节内容，避免过度依赖数据库页缓存的重复数据优化。
    private fun insertPayloadRow(
        database: SQLiteDatabase,
        index: Int,
    ): Unit {
        database.execSQL(
            "INSERT INTO demo_blocking_io(label, payload) VALUES(?, ?)",
            arrayOf(
                String.format(Locale.US, "row-%04d", index),
                ByteArray(databasePayloadBytes) { payloadIndex: Int ->
                    ((index + payloadIndex) % BYTE_PATTERN_MOD).toByte()
                },
            ),
        )
    }

    private companion object {
        /**
         * Demo 文件工作目录名。
         */
        private const val WORKING_DIR_NAME: String = "demo-blocking-io"

        /**
         * Demo 阻塞文件名。
         */
        private const val BLOCKING_FILE_NAME: String = "blocking-file.bin"

        /**
         * Demo SQLite 数据库文件名。
         */
        private const val DATABASE_NAME: String = "demo_blocking_io.db"

        /**
         * 生成 payload 的字节取模基数。
         */
        private const val BYTE_PATTERN_MOD: Int = 251

        /**
         * 默认每个文件块都执行一次 fsync。
         */
        private const val DEFAULT_SYNC_EVERY_CHUNKS: Int = 1

        /**
         * 默认写入 128 个文件块。
         */
        private const val DEFAULT_FILE_CHUNKS: Int = 128

        /**
         * 默认单文件块大小 256KB。
         */
        private const val DEFAULT_FILE_CHUNK_SIZE_BYTES: Int = 256 * 1024

        /**
         * 默认 SQLite 插入 800 行。
         */
        private const val DEFAULT_DATABASE_ROWS: Int = 800

        /**
         * 默认每行 SQLite payload 大小 8KB。
         */
        private const val DEFAULT_DATABASE_PAYLOAD_BYTES: Int = 8 * 1024
    }
}
