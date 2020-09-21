package com.ykai.kssocket.emitter

import kotlin.concurrent.thread

val DefaultIOEventEmitter: IOEventEmitter = IOEventEmitterImpl().also {
    thread(name = "DefaultIOEventEmitter", start = true, isDaemon = true) {
        it.run()
    }
}
