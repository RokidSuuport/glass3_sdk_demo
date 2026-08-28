package com.rokid.phone.data

import androidx.annotation.StringDef

class GB28181Extra  {
    var methodName = ""
    var message = ""
}



@Retention(AnnotationRetention.SOURCE)
@StringDef(GB28181API.syncP2G, GB28181API.syncG2P)
annotation class GB28181API{

    companion object{
        const val syncP2G:String = "syncP2G"
        const val syncG2P:String = "syncG2P"
    }
}
