package io.github.hyungkishin.transentia.infra.config

import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.apache.kafka.streams.kstream.ValueTransformerWithKey
import org.apache.kafka.streams.kstream.ValueTransformerWithKeySupplier
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.MDC

/**
 * Kafka Record 헤더에서 traceparent를 추출하여 MDC에 설정하는 Transformer
 * 
 * Kafka Streams는 일반 KafkaConsumer와 달리 observationEnabled가 적용되지 않아서
 * 수동으로 W3C Trace Context 헤더를 파싱해야 함
 * 
 * traceparent 형식: {version}-{traceId}-{spanId}-{flags}
 * 예: 00-130c0e23e150eb0ec69d4a4774cc1f03-47684cf7bc701ad3-01
 */
class TracingTransformer : ValueTransformerWithKey<String, TransferEventAvroModel, TransferEventAvroModel> {
    
    private lateinit var context: ProcessorContext

    override fun init(context: ProcessorContext) {
        this.context = context
    }

    override fun transform(key: String?, value: TransferEventAvroModel): TransferEventAvroModel {
        // 이전 MDC 정리
        MDC.clear()
        
        val headers = context.headers()
        val traceparent = headers.lastHeader("traceparent")?.value()?.let { String(it) }
        
        if (traceparent != null) {
            // traceparent 형식: 00-{traceId}-{spanId}-{flags}
            val parts = traceparent.split("-")
            if (parts.size >= 3) {
                MDC.put("traceId", parts[1])
                MDC.put("spanId", parts[2])
            }
        }
        
        return value
    }

    override fun close() {
        MDC.clear()
    }
}

/**
 * TracingTransformer를 생성하는 Supplier
 */
class TracingTransformerSupplier : ValueTransformerWithKeySupplier<String, TransferEventAvroModel, TransferEventAvroModel> {
    override fun get(): ValueTransformerWithKey<String, TransferEventAvroModel, TransferEventAvroModel> {
        return TracingTransformer()
    }
}
