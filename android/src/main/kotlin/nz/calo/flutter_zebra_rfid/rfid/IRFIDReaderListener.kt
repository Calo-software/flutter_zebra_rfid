package nz.calo.flutter_zebra_rfid.rfid

interface IRFIDReaderListener {
    fun newTagRead(epc : String?)
}