package io.github.hyungkishin.transentia.application.service

import io.github.hyungkishin.transentia.application.port.`in`.TransferRequestCommand
import io.github.hyungkishin.transentia.application.port.`in`.TransferUseCase
import io.github.hyungkishin.transentia.application.port.out.AccountBalanceRepository
import io.github.hyungkishin.transentia.application.port.out.TransactionRepository
import io.github.hyungkishin.transentia.application.port.out.TransferResponseCommand
import io.github.hyungkishin.transentia.common.snowflake.SnowflakeIdGenerator
import io.github.hyungkishin.transentia.domain.model.Transaction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// TODO:
//  as-is : Command-Driven 형태
//  to-be : Event-Driven 형태 로 변경 하기
@Service
class TransferService(
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val idGenerator: SnowflakeIdGenerator
) : TransferUseCase {

    @Transactional
    override fun requestTransfer(command: TransferRequestCommand): TransferResponseCommand {
        val sender = accountBalanceRepository.findByUserId(command.senderUserId())
            ?: error("송신자 계좌 정보를 찾을 수 없습니다")

        val receiver = accountBalanceRepository.findByUserId(command.receiverUserId())
            ?: error("수신자 계좌 정보를 찾을 수 없습니다")

        val amount = command.money()

        sender.withdraw(amount)
        receiver.deposit(amount)

        val transaction = Transaction.request(
            transactionId = idGenerator.generateTransferId(),
            senderUserId = command.senderUserId(),
            receiverUserId = command.receiverUserId(),
            amount = amount
        )

        accountBalanceRepository.save(sender)
        accountBalanceRepository.save(receiver)

        /**
         * TODO:
         *  1. Redis + Lua 선제적 잔액 차감 필요 (속도, 동시성 문제 방지)
         *  2. 이후 Kafka 이벤트 발행하기
         *  3. Consumer 가 RDB 에 Tx 히스토리 기록, 최종 상태 저장
         *  4. RDB 적재 실패 시 DLQ -> 재처리 or 관리자 개입 로직 추가
         */
        transaction.complete()

        val tx = transactionRepository.save(transaction)
        return TransferResponseCommand.from(tx)
    }

}
