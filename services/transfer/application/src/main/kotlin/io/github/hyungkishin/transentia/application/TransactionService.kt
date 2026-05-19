package io.github.hyungkishin.transentia.application

import io.github.hyungkishin.transentia.application.provided.TransactionRegister
import io.github.hyungkishin.transentia.application.provided.command.TransferRequestCommand
import io.github.hyungkishin.transentia.application.required.DailyTransferAmountCachePort
import io.github.hyungkishin.transentia.application.required.TransactionRepository
import io.github.hyungkishin.transentia.application.required.UserRepository
import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand
import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.transaction.Transaction
import io.github.hyungkishin.transentia.container.model.user.User
import io.github.hyungkishin.transentia.container.validator.transfer.TransferValidator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val userRepository: UserRepository,
    private val idGenerator: IdGenerator,
    private val eventPublisher: ApplicationEventPublisher,
    private val dailyTransferAmountCache: DailyTransferAmountCachePort,
) : TransactionRegister {

    @Transactional
    override fun createTransfer(command: TransferRequestCommand): TransferResponseCommand {
        val (sender, receiver) = loadUsers(command)
        val amount = command.amount()

        // LocalRule (송금자/수신자 블랙리스트, 일일 송금액) 적용
        val dailyAccumulated = dailyTransferAmountCache.getTodayAmount(sender.id.value)
        TransferValidator.validate(sender, receiver, amount, dailyAccumulated)

        val transaction = Transaction.of(
            SnowFlakeId(idGenerator.nextId()),
            sender.id,
            receiver.id,
            amount
        )

        sender.accountBalance.withdrawOrThrow(amount)
        receiver.accountBalance.deposit(amount)
        userRepository.save(sender)
        userRepository.save(receiver)

        val savedTransaction = transactionRepository.save(transaction)

        val completeEvent = transaction.complete()

        // 일일 누적치 갱신 (트랜잭션 커밋 전이지만 캐시는 over-estimate 가 under 보다 안전)
        dailyTransferAmountCache.addTodayAmount(sender.id.value, amount.money.rawValue)

        // 이벤트 발행 (커밋 후 별도 스레드에서 Kafka 전송 시도) - @see TransferOutboxEventHandler
        // Kafka 전송 실패 시 Outbox 저장 - @see KafkaTransferEventPublisher
        eventPublisher.publishEvent(completeEvent)

        return TransferResponseCommand.from(savedTransaction)
    }

    /**
     * 데드락 방지를 위한 정렬된 락 획득
     *
     * 문제 시나리오 (정렬 없이 순차 락 획득 시):
     * - Thread A: 계좌 A → 계좌 B 송금 (A 락 획득 후 B 락 대기)
     * - Thread B: 계좌 B → 계좌 A 송금 (B 락 획득 후 A 락 대기)
     * - 결과: 데드락 발생
     *
     * 해결: 계좌번호 오름차순 정렬 후 락 획득
     * - 모든 트랜잭션이 동일한 순서로 락 획득 → 데드락 원천 차단
     */
    private fun loadUsers(command: TransferRequestCommand): Pair<User, User> {
        val senderAccount = command.senderAccountNumber
        val receiverAccount = command.receiverAccountNumber

        // 계좌번호 오름차순 정렬하여 락 획득 순서 고정
        val (firstAccount, secondAccount) = if (senderAccount < receiverAccount) {
            senderAccount to receiverAccount
        } else {
            receiverAccount to senderAccount
        }

        // 정렬된 순서로 패시미스틱 락 획득
        val firstUser = userRepository.findByAccountNumberWithLock(firstAccount)
            ?: throw DomainException(
                CommonError.NotFound("account_balance", firstAccount),
                "계좌 정보를 찾을 수 없습니다: $firstAccount"
            )
        val secondUser = userRepository.findByAccountNumberWithLock(secondAccount)
            ?: throw DomainException(
                CommonError.NotFound("account_balance", secondAccount),
                "계좌 정보를 찾을 수 없습니다: $secondAccount"
            )

        // sender/receiver 순서로 반환
        return if (senderAccount < receiverAccount) {
            Pair(firstUser, secondUser)
        } else {
            Pair(secondUser, firstUser)
        }
    }

    @Transactional(readOnly = true)
    override fun findTransfer(transactionId: Long): TransferResponseCommand {
        val tx = transactionRepository.findById(transactionId)
            ?: throw DomainException(
                CommonError.NotFound("transaction", transactionId.toString()),
                "송금 이력이 존재하지 않습니다. id=$transactionId"
            )
        return TransferResponseCommand.from(tx)
    }

}
