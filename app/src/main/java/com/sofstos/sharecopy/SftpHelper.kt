package com.sofstos.sharecopy

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.InputStream
import java.util.EnumSet

/**
 * Простой SFTP-клиент поверх sshj. Проверка ключа хоста отключена (PromiscuousVerifier) —
 * это упрощение для личного использования в доверенной сети; в открытых/чужих сетях
 * это означает отсутствие защиты от подмены сервера (MITM).
 */
object SftpHelper {

    data class SftpResult(val success: Boolean, val message: String)

    private fun connect(host: String, port: Int, user: String, password: String): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 8000
        ssh.timeout = 15000
        ssh.connect(host, port)
        ssh.authPassword(user, password)
        return ssh
    }

    fun testConnection(host: String, port: Int, user: String, password: String, remotePath: String): SftpResult {
        var ssh: SSHClient? = null
        return try {
            ssh = connect(host, port, user, password)
            ssh.newSFTPClient().use { sftp ->
                sftp.mkdirs(remotePath.ifBlank { "/" })
            }
            SftpResult(true, "OK")
        } catch (e: Exception) {
            SftpResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Загружает один поток байт [input] под именем [fileName] в папку [remoteDir]. */
    fun uploadStream(dest: Destination, remoteDir: String, fileName: String, input: InputStream): SftpResult {
        var ssh: SSHClient? = null
        return try {
            ssh = connect(dest.sftpHost!!, dest.sftpPort, dest.sftpUser!!, dest.sftpPassword!!)
            ssh.newSFTPClient().use { sftp ->
                val dir = remoteDir.ifBlank { "/" }
                sftp.mkdirs(dir)
                val unique = uniqueName(sftp, dir, fileName)
                val remotePath = joinRemote(dir, unique)
                sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { handle ->
                    handle.RemoteFileOutputStream().use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
            }
            SftpResult(true, "OK")
        } catch (e: Exception) {
            SftpResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Рекурсивно загружает папку [srcRoot] целиком в [remoteBaseDir] (создаёт там подпапку с её именем). */
    fun uploadFolder(dest: Destination, remoteBaseDir: String, srcRoot: File, move: Boolean): SftpResult {
        var ssh: SSHClient? = null
        return try {
            ssh = connect(dest.sftpHost!!, dest.sftpPort, dest.sftpUser!!, dest.sftpPassword!!)
            ssh.newSFTPClient().use { sftp ->
                val dir = remoteBaseDir.ifBlank { "/" }
                sftp.mkdirs(dir)
                val uniqueRootName = uniqueName(sftp, dir, srcRoot.name)
                val remoteRootPath = joinRemote(dir, uniqueRootName)
                uploadTree(sftp, srcRoot, remoteRootPath)
            }
            if (move) srcRoot.deleteRecursively()
            SftpResult(true, "OK")
        } catch (e: Exception) {
            SftpResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            try { ssh?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun uploadTree(sftp: SFTPClient, localDir: File, remotePath: String) {
        sftp.mkdirs(remotePath)
        val children = localDir.listFiles() ?: return
        for (child in children) {
            val childRemote = joinRemote(remotePath, child.name)
            if (child.isDirectory) {
                uploadTree(sftp, child, childRemote)
            } else {
                sftp.open(childRemote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { handle ->
                    handle.RemoteFileOutputStream().use { out ->
                        child.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                    }
                }
            }
        }
    }

    private fun joinRemote(dir: String, name: String): String {
        val d = dir.trimEnd('/')
        return if (d.isBlank()) "/$name" else "$d/$name"
    }

    private fun uniqueName(sftp: SFTPClient, dir: String, name: String): String {
        val existing = try {
            sftp.ls(dir).map { it.name }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }
}
