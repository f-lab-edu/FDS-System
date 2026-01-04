package io.github.hyungkishin.transentia.infra.config

import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.apache.kafka.streams.processor.api.FixedKeyProcessor
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier
import org.apache.kafka.streams.processor.api.FixedKeyRecord
import org.slf4j.MDC

/**
 * Kafka Record 헤더에서 traceparent를 추출하여 MDC에 설정하는 Processor
 * 
 * Kafka Streams는 일반 KafkaConsumer와 달리 observationEnabled가 적용되지 않아서
 * 수동으로 W3C Trace Context 헤더를 파싱해야 함
 * 
 * traceparent 형식: {version}-{traceId}-{spanId}-{flags}
 * 예: 00-130c0e23e150eb0ec69d4a4774cc1f03-47684cf7bc701ad3-01
 * 
 * Note: Kafka Streams 3.3+에서 transformValues()가 deprecated되어
 * FixedKeyProcessor API로 마이그레이션
 */
class TracingProcessor : FixedKeyProcessor<String, TransferEventAvroModel, TransferEventAvroModel> {
    
    private lateinit var context: FixedKeyProcessorContext<String, TransferEventAvroModel>

    override fun init(context: FixedKeyProcessorContext<String, TransferEventAvroModel>) {
        this.context = context
    }

    override fun process(record: FixedKeyRecord<String, TransferEventAvroModel>) {
        // 이전 MDC 정리 (스레드 풀 재사용 대비)
        MDC.clear()
        
        val traceparent = record.headers().lastHeader("traceparent")?.value()?.let { String(it) }
        
        if (traceparent != null) {
            // traceparent 형식: 00-{traceId}-{spanId}-{flags}
            val parts = traceparent.split("-")
            if (parts.size >= 3) {
                MDC.put("traceId", parts[1])
                MDC.put("spanId", parts[2])
            }
        }
        
        // 다음 processor로 전달
        context.forward(record)
    }

    override fun close() {
        MDC.clear()
    }
}

/**
 * TracingProcessor를 생성하는 Supplier
 */
class TracingProcessorSupplier : FixedKeyProcessorSupplier<String, TransferEventAvroModel, TransferEventAvroModel> {
    override fun get(): FixedKeyProcessor<String, TransferEventAvroModel, TransferEventAvroModel> {
        return TracingProcessor()
    }
}
