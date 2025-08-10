//package io.github.hyungkishin.transentia.application.exception
//
//import io.github.hyungkishin.transentia.shared.exception.message.ErrorCode
//
//enum class TransferErrorCode(
//    override val code: String,
//    override val message: String,
//    override val httpStatus: HttpStatus
//) : ErrorCode {
//
//    INVALID_CATEGORY_NAME("INVALID_CATEGORY_NAME", "유효하지 않은 카테고리 이름", HttpStatus.BAD_REQUEST),
//    INVALID_CATEGORY_SORT_ORDER("INVALID_CATEGORY_SORT_ORDER", "유효하지 않은 카테고리 정렬", HttpStatus.BAD_REQUEST),
//    NOT_FOUND_CATEGORY("NOT_FOUND_CATEGORY", "존재하지 않은 카테고리", HttpStatus.NOT_FOUND),
//    INVALID_REQUEST("INVALID_REQUEST", "잘못된 요청", HttpStatus.BAD_REQUEST)
//    ;
//
//    companion object {
//        private const val title = "상품 오류"
//    }
//
//    constructor(message: String) : this(title, message, HttpStatus.BAD_GATEWAY)
//    constructor(message: String, status: HttpStatus) : this(title, message, status)
//}