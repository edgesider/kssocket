package com.ykai.kssocket

open class IOEventEmitterException : Exception()
class UnregisterException : IOEventEmitterException()
class NotRegisterException : IOEventEmitterException()
class RegisteredException : IOEventEmitterException()
