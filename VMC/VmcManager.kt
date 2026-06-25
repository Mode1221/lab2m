package com.example.vendingmachine.vmc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList
import java.util.Queue

class VmcManager(
    val context: Context,
    private val errorHandler: VmcErrorHandler
) {
    private var mInputStream: InputStream? = null
    private var mOutputStream: OutputStream? = null

    @Volatile private var isRunning = false

    private var packNo: Int = 1
    private var pendingSelectionNum: Int? = null
    private var macroStep = 0

    private val selectionQueue: Queue<Int> = LinkedList()
    private var isProcessing = false

    private val readBuffer = ByteArrayOutputStream()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { handleTimeout() }

    fun connect() {
        if (isRunning) return

        try {
            val devicePath = "/dev/ttyS4"
            val deviceFile = File(devicePath)

            if (!deviceFile.exists()) {
                errorHandler.handle(VmcError.PortOpenFail(devicePath))
                return
            }

            mInputStream = FileInputStream(deviceFile)
            mOutputStream = FileOutputStream(deviceFile)
            isRunning = true

            Log.d("VMC_SERIAL", "$devicePath 포트 열기 성공")
            startReading()
            sendSyncCommand()

        } catch (e: Exception) {
            errorHandler.handle(VmcError.Disconnected())
        }
    }

    fun disconnect() {
        Log.d("VMC_SERIAL", "포트 연결 해제 및 리소스 반납")
        isRunning = false
        mainHandler.removeCallbacks(timeoutRunnable)
        try { mInputStream?.close() } catch (_: Exception) {}
        try { mOutputStream?.close() } catch (_: Exception) {}
    }

    private fun startReading() {
        Thread {
            val buffer = ByteArray(1024)
            while (isRunning) {
                try {
                    val numBytes = mInputStream?.read(buffer) ?: 0
                    if (numBytes > 0) {
                        readBuffer.write(buffer, 0, numBytes)
                        processBuffer()
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        errorHandler.handle(VmcError.Disconnected())
                    } else {
                        Log.d("VMC_SERIAL", "정상적인 연결 해제로 인한 스레드 종료")
                    }
                    break
                }
            }
        }.start()
    }

    private fun processBuffer() {
        var data = readBuffer.toByteArray()

        while (data.size >= 5) {
            var startIdx = -1
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFA.toByte() && data[i + 1] == 0xFB.toByte()) {
                    startIdx = i
                    break
                }
            }

            if (startIdx == -1) {
                readBuffer.reset()
                break
            }

            if (startIdx > 0) {
                data = data.copyOfRange(startIdx, data.size)
                readBuffer.reset()
                readBuffer.write(data)
            }

            if (data.size < 4) break

            val textLen = data[3].toInt() and 0xFF
            val expectedSize = 4 + textLen + 1

            if (data.size < expectedSize) {
                break
            }

            val packet = data.copyOfRange(0, expectedSize)
            val xor = packet[expectedSize - 1]
            val calcXor = calculateXor(packet.copyOfRange(0, expectedSize - 1))

            if (xor == calcXor) {
                handleVmcPacket(packet)
                data = data.copyOfRange(expectedSize, data.size)
            } else {
                errorHandler.handle(VmcError.ChecksumFail())
                data = data.copyOfRange(2, data.size)
            }

            readBuffer.reset()
            readBuffer.write(data)
        }
    }

    private fun handleVmcPacket(packet: ByteArray) {
        val command = packet[2]

        when (command) {
            0x41.toByte() -> processMacro()

            0x04.toByte() -> {
                sendAck()
                val status = packet[5]

                if (isProcessing) {
                    when (macroStep) {
                        2 -> {
                            if (status == 0x02.toByte()) {
                                Log.d("VMC_STATUS", "물리적 배출 완료(0x02). 최종 수취 대기 중...")
                                macroStep = 3
                            }
                        }
                        3 -> {
                            if (status == 0x24.toByte()) {
                                Log.d("VMC_STATUS", "최종 완료(0x24) 확인.")
                                finishCurrentDispense(success = true)
                            }
                        }
                    }
                }
            }
            0x71.toByte(), 0x42.toByte() -> { }
            else -> sendAck()
        }
    }

    private fun processMacro() {
        val target = pendingSelectionNum
        if (target == null) {
            sendAck()
            return
        }

        if (macroStep == 1) {
            val command = 0x06.toByte()
            val text = byteArrayOf(
                packNo.toByte(), 0x01.toByte(), 0x01.toByte(),
                (target shr 8).toByte(), (target and 0xFF).toByte()
            )

            val fullPacketWithoutXor = byteArrayOf(0xFA.toByte(), 0xFB.toByte(), command, text.size.toByte()) + text
            val xor = calculateXor(fullPacketWithoutXor)
            writeData(fullPacketWithoutXor + byteArrayOf(xor))

            Log.d("VMC_SERIAL", "명령(0x06) 송신 - 슬롯: $target")
            packNo = if (packNo >= 255) 1 else packNo + 1

            macroStep = 2

            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, 1)
        } else {
            sendAck()
        }
    }

    private fun finishCurrentDispense(success: Boolean) {
        mainHandler.removeCallbacks(timeoutRunnable)

        if (!success) {
            errorHandler.handle(VmcError.Timeout(pendingSelectionNum ?: -1))
        }

        isProcessing = false
        macroStep = 0
        pendingSelectionNum = null

        Thread {
            Thread.sleep(500)
            startNextDispense()
        }.start()
    }

    private fun handleTimeout() {
        if (isProcessing) {
            finishCurrentDispense(success = false)
        }
    }

    fun dispense(selectionNum: Int) {
        selectionQueue.offer(selectionNum)
        if (isProcessing && pendingSelectionNum == null) {
            isProcessing = false
        }
        if (!isProcessing) {
            startNextDispense()
        }
    }

    private fun startNextDispense() {
        val nextSlot = selectionQueue.poll()
        if (nextSlot != null) {
            isProcessing = true
            pendingSelectionNum = nextSlot
            macroStep = 1
            Log.d("VMC_SERIAL", "배출 시작 - 슬롯: $nextSlot")
        } else {
            isProcessing = false
        }
    }

    private fun sendInstantCommand(text: ByteArray, command: Byte) {
        val fullPacketWithoutXor = byteArrayOf(0xFA.toByte(), 0xFB.toByte(), command, text.size.toByte()) + text
        val xor = calculateXor(fullPacketWithoutXor)
        writeData(fullPacketWithoutXor + byteArrayOf(xor))
        packNo = if (packNo >= 255) 1 else packNo + 1
    }

    private fun writeData(packet: ByteArray) {
        try {
            mOutputStream?.write(packet)
            mOutputStream?.flush()
        } catch (e: Exception) {
            errorHandler.handle(VmcError.Disconnected())
        }
    }

    private fun sendSyncCommand() {
        val text = byteArrayOf(packNo.toByte())
        sendInstantCommand(text, 0x31.toByte())
    }

    private fun sendAck() {
        val ackPacket = byteArrayOf(0xFA.toByte(), 0xFB.toByte(), 0x42.toByte(), 0x00.toByte(), 0x43.toByte())
        writeData(ackPacket)
    }

    private fun calculateXor(data: ByteArray): Byte {
        var xor = 0
        for (b in data) xor = xor xor b.toInt()
        return xor.toByte()
    }
}