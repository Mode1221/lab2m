package com.example.vendingmachine.vmc

import android.util.Log

class VmcErrorHandler(
    private val showNativePopup: (String) -> Unit,
    private val sendWebResult: (String, Int, String) -> Unit
) {
    fun handle(error: VmcError) {
        Log.e("VMC_ERROR", "[${error.code}] ${error.message}")

        when (error) {
            is VmcError.Timeout -> {
                sendWebResult("ERROR", error.slot, error.message)
            }
            is VmcError.PortOpenFail,
            is VmcError.Disconnected -> {
                // 포트가 끊기거나 안 열리는 건 시스템 에러이므로 검은 화면 띄움
                showNativePopup(error.code)
            }
            is VmcError.ChecksumFail -> { }
        }
    }
}