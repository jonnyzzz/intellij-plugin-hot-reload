package com.jonnyzzz.intellij.hotreload

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IdeScriptManagerWorkaroundText : BasePlatformTestCase() {
    fun testWeCanReset() {
        dropCache()
    }
}
