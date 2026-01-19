package com.appsquadz.educryptmedia.utils



object AES {

    var strArrayKeyLib = "!*@#)($^%1fgv&C="
    var strArrayvectorLib = "?\\:><{}@#Vjekl/4"


    fun generateLibkeyAPI(token: String): String {
        var finalKey = ""
        val parts: String = token
        for (c in parts.toCharArray()) {
            finalKey = finalKey + strArrayKeyLib.toCharArray()[c.toString().toInt()]
        }
        return finalKey
    }

    fun generateLibVectorAPI(token: String): String {
        var finalKey = ""
        val parts: String = token
        for (c in parts.toCharArray()) {
            finalKey = finalKey + strArrayvectorLib.toCharArray()[c.toString().toInt()]
        }
        return finalKey
    }


}    
