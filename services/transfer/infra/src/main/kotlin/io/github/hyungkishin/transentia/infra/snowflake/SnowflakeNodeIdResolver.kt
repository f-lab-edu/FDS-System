package io.github.hyungkishin.transentia.infra.snowflake

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * Snowflake node_id 자동 할당.
 *
 * MULTI-INSTANCE.md §1 의 'node_id 충돌' 자리 해소.
 * Snowflake 64bit 의 10bit node_id 영역(0~1023) 안에서 인스턴스 고유 ID 를
 * 결정한다.
 *
 * 우선순위:
 *  1. `id.snowflake.node-id` 가 명시적으로 양수면 그대로 사용 (운영자 강제 지정).
 *  2. `SNOWFLAKE_NODE_ID` 환경변수가 0보다 크면 그 값.
 *  3. hostname 패턴 `name-<digit>` (예: pod-2, transfer-api-3) 가 매칭되면 그 숫자.
 *  4. hostname 의 hashCode → 0~1023 mod (마지막 안전망).
 *
 * 운영 권장:
 *  - Kubernetes StatefulSet: pod ordinal (0, 1, 2 …) 이 hostname 에 들어가 3번 매칭.
 *  - Docker Compose: container_name 에 -1/-2/-3 suffix → 3번 매칭.
 *  - 그 외: 4번 hashCode 폴백 — 충돌 확률 ~1/1024.
 *
 * 인스턴스 1024대 이상 운영 시: 별도 외부 coordinator (ZooKeeper / etcd) 필요.
 */
@Component
class SnowflakeNodeIdResolver(
    @Value("\${id.snowflake.node-id:0}") private val configuredNodeId: Long,
    @Value("\${SNOWFLAKE_NODE_ID:0}") private val envNodeId: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(): Long {
        val (source, value) = pick()
        val clamped = value.absoluteValue % MAX_NODE_ID
        log.info("[snowflake] node_id={} (source={}, hostname={})", clamped, source, hostnameSafe())
        return clamped
    }

    private fun pick(): Pair<String, Long> {
        if (configuredNodeId > 0) return "configured" to configuredNodeId
        if (envNodeId > 0) return "env" to envNodeId

        val host = hostnameSafe()
        ORDINAL_REGEX.find(host)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return "hostname-ordinal" to it
        }

        return "hostname-hash" to (host.hashCode().toLong().absoluteValue % MAX_NODE_ID)
    }

    private fun hostnameSafe(): String = try {
        InetAddress.getLocalHost().hostName ?: fallbackHost()
    } catch (e: Exception) {
        fallbackHost()
    }

    private fun fallbackHost(): String =
        System.getenv("HOSTNAME") ?: "node-${Random.nextInt(0, 1024)}"

    companion object {
        private const val MAX_NODE_ID = 1024L
        // 'pod-3', 'transfer-api-2', 'fds-instance-7' 등의 끝 숫자
        private val ORDINAL_REGEX = Regex("-(\\d+)(?:[^\\d].*)?$")
    }
}
