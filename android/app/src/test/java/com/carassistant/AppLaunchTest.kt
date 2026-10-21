package com.carassistant

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动冒烟测试。
 *
 * 编译通过只说明语法对，说明不了「点开图标能进去」——
 * ViewModel 构造、Room 建库、DataStore 初始化、Compose 首帧渲染，
 * 任何一步抛异常用户看到的就是闪退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchTest {

    @Test
    fun `main activity starts and renders first frame`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
        // 走到这里说明 Activity 已 create + start + resume，Compose 首帧没抛
        controller.pause().stop().destroy()
    }
}
