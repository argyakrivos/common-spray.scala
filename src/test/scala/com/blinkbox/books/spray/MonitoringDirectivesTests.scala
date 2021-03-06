package com.blinkbox.books.spray

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

import com.blinkbox.books.spray.v2.`application/vnd.blinkbox.books.v2+json`
import com.blinkbox.books.test.MockitoSyrup
import com.typesafe.scalalogging.Logger
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.FunSuite
import org.scalatest.mock.MockitoSugar
import org.slf4j.{Logger => Slf4jLogger, MDC}
import spray.http.{CacheDirectives, HttpEncodings, HttpEncodingRange, HttpChallenge}
import spray.http.HttpHeaders._
import spray.http.StatusCodes._
import spray.http._
import spray.routing.AuthenticationFailedRejection
import spray.routing.AuthenticationFailedRejection.CredentialsMissing
import spray.routing.Directives._
import spray.testkit.ScalatestRouteTest
import com.blinkbox.books.spray.v2.Implicits.throwableMarshaller

class MonitoringDirectivesTests extends FunSuite with ScalatestRouteTest with MockitoSyrup with MonitoringDirectives {

  test("monitor logs an info message with the HTTP endpoint, status and duration for successful requests") {
    val messageRef = new AtomicReference[String]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer((i: InvocationOnMock) => messageRef.set(i.getArguments.head.asInstanceOf[String]))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> { monitor(new MonitorMagnet(log, throwableMarshaller)) { complete(OK) } } ~> check {
      assert(messageRef.get() matches "GET /path returned 200 OK in [0-9]+ms")
    }
  }

  test("monitor logs an info message with the HTTP endpoint, status and duration for 401 Unauthorized errors") {
    val messageRef = new AtomicReference[String]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer((i: InvocationOnMock) => messageRef.set(i.getArguments.head.asInstanceOf[String]))

    implicit val log = Logger(mockLog)
    val rejection = AuthenticationFailedRejection(CredentialsMissing, `WWW-Authenticate`(HttpChallenge(scheme = "http", realm = "test")) :: Nil)
    Get("/path?q=1") ~> { monitor() { reject(rejection) } } ~> check {
      assert(messageRef.get() matches "GET /path returned 401 Unauthorized in [0-9]+ms")
    }
  }

  test("monitor logs a warning message with the HTTP endpoint, status and duration for client errors except 401 Unauthorized") {
    val messageRef = new AtomicReference[String]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isWarnEnabled).thenAnswer(() => true)
    when(mockLog.warn(any(classOf[String]))).thenAnswer((i: InvocationOnMock) => messageRef.set(i.getArguments.head.asInstanceOf[String]))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> { monitor() { reject() } } ~> check {
      assert(messageRef.get() matches "GET /path returned 404 Not Found in [0-9]+ms")
    }
  }

  test("monitor logs an error message with the HTTP endpoint, status and duration for server errors") {
    val messageRef = new AtomicReference[String]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isErrorEnabled).thenAnswer(() => true)
    when(mockLog.error(any(classOf[String]))).thenAnswer((i: InvocationOnMock) => messageRef.set(i.getArguments.head.asInstanceOf[String]))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> { monitor() { complete(InternalServerError) } } ~> check {
      assert(messageRef.get() matches "GET /path returned 500 Internal Server Error in [0-9]+ms")
    }
  }

  test("monitor logs an error message with the HTTP endpoint, status and duration when an exception is thrown") {
    val messageRef = new AtomicReference[String]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isErrorEnabled).thenAnswer(() => true)
    when(mockLog.error(any(classOf[String]))).thenAnswer((i: InvocationOnMock) => messageRef.set(i.getArguments.head.asInstanceOf[String]))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> { monitor() { dynamic { throw new Exception("o noes!") } } } ~> check {
      assert(messageRef.get() matches "GET /path returned 500 Internal Server Error in [0-9]+ms")
    }
  }

  test("monitor adds key HTTP properties to the MDC context") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpMethod") == "GET")
      assert(mdc.get("httpPath") == "/path")
      assert(mdc.get("httpPathAndQuery") == "/path?q=1")
      assert(mdc.get("httpStatus") == "200")
      assert(mdc.get("httpApplicationTime").toString matches "[0-9]+")
    }
  }

  test("monitor adds interesting HTTP request headers to the MDC context") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~>
      `Accept-Encoding`(HttpEncodingRange(HttpEncodings.gzip)) ~>
      `User-Agent`("MyClient/1.1") ~>
      RawHeader("Via", "1.1 www.example.org") ~>
      `X-Forwarded-For`("192.168.1.27") ~>
      RawHeader("X-Requested-With", "XmlHttpRequest") ~>
      { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpAcceptEncoding").asInstanceOf[String] contains "gzip")
      assert(mdc.get("httpUserAgent").asInstanceOf[String] contains "MyClient/1.1")
      assert(mdc.get("httpVia").asInstanceOf[String] contains "1.1 www.example.org")
      assert(mdc.get("httpXForwardedFor").asInstanceOf[String] contains "192.168.1.27")
      assert(mdc.get("httpXRequestedWith").asInstanceOf[String] contains "XmlHttpRequest")
    }
  }

  test("monitor adds interesting HTTP response headers to the MDC context") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~>
      `Accept-Encoding`(HttpEncodingRange(HttpEncodings.gzip)) ~> { monitor() {
      respondWithHeaders(
        `Cache-Control`(CacheDirectives.`no-store`),
        `Content-Length`(1234),
        `WWW-Authenticate`(HttpChallenge(scheme = "http", realm = "test"))
      ) { complete(OK) }
    } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpCacheControl").asInstanceOf[String] contains "no-store")
      assert(mdc.get("httpContentLength").asInstanceOf[String] contains "1234")
      assert(mdc.get("httpWWWAuthenticate").asInstanceOf[String] contains "http realm=test")
    }
  }

  test("monitor adds the client IP if the Remote-Address header is valid") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> `Remote-Address`("192.168.0.1") ~> { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpClientIP") == "192.168.0.1")
    }
  }

  test("monitor does not cause the request to fail if the Remote-Address header is invalid") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> RawHeader("Remote-Address", "invalid") ~> { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpStatus") == "200")
    }
  }

  test("monitor adds the client IP if the X-Forwarded-For header is valid") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> `X-Forwarded-For`("192.168.1.1", "192.168.1.2") ~> { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpClientIP") == "192.168.1.1")
    }
  }

  test("monitor does not cause the request to fail if the X-Forwarded-For header is invalid") {
    val mdcRef = new AtomicReference[java.util.Map[_, _]]()
    val mockLog = mock[Slf4jLogger]
    when(mockLog.isInfoEnabled).thenAnswer(() => true)
    when(mockLog.info(any(classOf[String]))).thenAnswer(() => mdcRef.set(MDC.getCopyOfContextMap))

    implicit val log = Logger(mockLog)
    Get("/path?q=1") ~> RawHeader("X-Forwarded-For", "invalid") ~> { monitor() { complete(OK) } } ~> check {
      val mdc = mdcRef.get()
      assert(mdc.get("httpStatus") == "200")
    }
  }

  test("MonitorExceptionHandler returns JSON message body for 500 Server error responses with v2 API Throwable marshaller") {
    implicit val log = Logger(mock[Slf4jLogger])
    Get("/path") ~> { monitor() { failWith(new RuntimeException("test exception")) } } ~> check {
      assert(status == InternalServerError)
      assert(mediaType == `application/vnd.blinkbox.books.v2+json`)
      assert(body.asString == """{"code":"InternalServerError","developerMessage":"There was an internal server error."}""")
    }
  }

  test("MonitorExceptionHandler returns JSON message body for Server error responses with v2 API Throwable marshaller") {
    implicit val log = Logger(mock[Slf4jLogger])
    Get("/path") ~> { monitor() { failWith(new RequestProcessingException(ServiceUnavailable)) } } ~> check {
      assert(status == ServiceUnavailable)
      assert(mediaType == `application/vnd.blinkbox.books.v2+json`)
      assert(body.asString == """{"code":"ServiceUnavailable","developerMessage":"The server is currently unavailable (because it is overloaded or down for maintenance)."}""")
    }
  }

  test("MonitorExceptionHandler returns JSON message body for Client error responses with v2 API Throwable marshaller") {
    implicit val log = Logger(mock[Slf4jLogger])
    Get("/path") ~> { monitor() { failWith(new IllegalRequestException(BadRequest)) } } ~> check {
      assert(status == BadRequest)
      assert(mediaType == `application/vnd.blinkbox.books.v2+json`)
      assert(body.asString == """{"code":"BadRequest","developerMessage":"The request contains bad syntax or cannot be fulfilled."}""")
    }
  }

  test("MonitorExceptionHandler returns no body for 500 Server error responses with v1 API Throwable marshaller") {
    val log = Logger(mock[Slf4jLogger])
    val marshaller = com.blinkbox.books.spray.v1.Implicits.throwableMarshaller
    Get("/path") ~> { monitor(log, marshaller) { failWith(new RuntimeException("test exception")) } } ~> check {
      assert(status == InternalServerError)
      assert(entity.isEmpty)
    }
  }

  test("MonitorExceptionHandler returns no body for Server error responses with v1 API Throwable marshaller") {
    val log = Logger(mock[Slf4jLogger])
    val marshaller = com.blinkbox.books.spray.v1.Implicits.throwableMarshaller
    Get("/path") ~> { monitor(log, marshaller) { failWith(new RequestProcessingException(ServiceUnavailable)) } } ~> check {
      assert(status == ServiceUnavailable)
      assert(entity.isEmpty)
    }
  }

  test("MonitorExceptionHandler returns no body for Client error responses with v1 API Throwable marshaller") {
    val log = Logger(mock[Slf4jLogger])
    val marshaller = com.blinkbox.books.spray.v1.Implicits.throwableMarshaller
    Get("/path") ~> { monitor(log, marshaller) { failWith(new IllegalRequestException(BadRequest)) } } ~> check {
      assert(status == BadRequest)
      assert(entity.isEmpty)
    }
  }

  test("MonitorExceptionHandler converts RejectedExecutionException into 503 Service Unavailable") {
    implicit val log = Logger(mock[Slf4jLogger])
    Get("/path") ~> { monitor() { failWith(new RejectedExecutionException) } } ~> check {
      assert(status == ServiceUnavailable)
      assert(mediaType == `application/vnd.blinkbox.books.v2+json`)
      assert(body.asString == """{"code":"ServiceUnavailable","developerMessage":"The server is currently unavailable (because it is overloaded or down for maintenance)."}""")
    }
  }
}
