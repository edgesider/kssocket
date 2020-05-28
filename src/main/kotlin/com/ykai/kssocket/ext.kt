package com.ykai.kssocket

import kotlin.concurrent.thread

fun IOEventEmitter.runInNewThread() =
    thread(start = true, isDaemon = true) {
        run()
    }
