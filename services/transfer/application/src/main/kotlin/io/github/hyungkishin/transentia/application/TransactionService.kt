package io.github.hyungkishin.transentia.application

import io.github.hyungkishin.transentia.application.mapper.OutboxEventMapper
import io.github.hyungkishin.transentia.application.provided.TransactionRegister
import io.github.hyungkishin.transentia.application.provided.command.TransferRequestCommand
import io.github.hyungkishin.transentia.application.required.TransactionRepository
import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.application.required.UserRepository
import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand
import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.transaction.Transaction
import io.github.hyungkishin.transentia.container.validator.transfer.TransferValidator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val userRepository: UserRepository,
    private val outboxRepository: TransferEventsOutboxRepository,
    private val outboxEventMapper: OutboxEventMapper,
    private val idGenerator: IdGenerator,
    private val eventPublisher: ApplicationEventPublisher,
) : TransactionRegister {

    @Transactional
    override fun createTransfer(command: TransferRequestCommand): TransferResponseCommand {
        val (sender, receiver) = loadUsers(command)
        val amount = command.amount()

        // LocalRule ( 송금자/수신자 블랙리스트, 일일 송금액) 적용
        TransferValidator.validate(sender, receiver, amount)

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

        // outbox 먼저 저장
        saveToOutbox(completeEvent)

        // 이벤트 발행 (커밋 후 별도 스레드에서 Kafka 전송) - @see TransferOutboxEventHandler
//        eventPublisher.publishEvent(completeEvent)

        return TransferResponseCommand.from(savedTransaction)
    }

    private fun saveToOutbox(event: TransferCompleted) {
        try {
            val outboxEvent = outboxEventMapper.toOutboxEvent(event)
            outboxRepository.save(outboxEvent, Instant.now())
        } catch (e: Exception) {
            throw DomainException(
                CommonError.Conflict("outbox_save_failed"),
                "송금 처리 중 시스템 오류가 발생했습니다.",
                e
            )
        }
    }

    private fun loadUsers(command: TransferRequestCommand) =
        Pair(
            userRepository.findById(command.senderId)
                ?: throw DomainException(
                    CommonError.NotFound("account_balance", command.senderId.toString()),
                    "송신자 정보를 찾을 수 없습니다."
                ),
            userRepository.findByAccountNumber(command.receiverAccountNumber)
                ?: throw DomainException(
                    CommonError.NotFound("account_balance", command.receiverAccountNumber),
                    "수신자 계좌 정보를 찾을 수 없습니다."
                )
        )

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
