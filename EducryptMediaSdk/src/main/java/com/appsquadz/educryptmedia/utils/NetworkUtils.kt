package com.appsquadz.educryptmedia.utils

import java.io.IOException

sealed class ApiException(message: String) : IOException(message)

class NoConnectivityException : ApiException("No network connectivity")
class NoInternetException(message: String) : ApiException(message)
class TimeoutException(message: String) : ApiException(message)
class NetworkException(message: String) : ApiException(message)
class SslException(message: String) : ApiException(message)
class BadRequestException(message: String) : ApiException(message)
class UnauthorizedException(message: String) : ApiException(message)
class ForbiddenException(message: String) : ApiException(message)
class NotFoundException(message: String) : ApiException(message)
class RequestTimeoutException(message: String) : ApiException(message)
class TooManyRequestsException(message: String) : ApiException(message)
class ServerException(message: String) : ApiException(message)
class HttpException(val code: Int, message: String) : ApiException(message)
class UnknownException(message: String) : ApiException(message)