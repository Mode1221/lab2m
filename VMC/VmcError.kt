package com.example.vendingmachine.vmc

sealed class VmcError(val code: String, val message: String) {
    class Timeout(val slot: Int) : VmcError("ERR_TIMEOUT_$slot", "슬롯 $slot 배출 무응답")
    class PortOpenFail(path: String) : VmcError("ERR_PORT_OPEN", "$path 포트 열기 실패")
    class Disconnected : VmcError("ERR_DISCONNECT", "통신 포트 끊김")
    class ChecksumFail : VmcError("WARN_CHECKSUM", "패킷 체크섬 오류 (노이즈)")
}