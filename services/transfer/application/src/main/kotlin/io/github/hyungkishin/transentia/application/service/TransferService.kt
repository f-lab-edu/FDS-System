package io.github.hyungkishin.transentia.application.service

import io.github.hyungkishin.transentia.application.port.`in`.TransferRequestCommand
import io.github.hyungkishin.transentia.application.port.`in`.TransferService
import io.github.hyungkishin.transentia.application.port.out.AccountBalanceRepository
import io.github.hyungkishin.transentia.application.port.out.TransactionRepository
import io.github.hyungkishin.transentia.application.port.out.TransferResponseCommand
import io.github.hyungkishin.transentia.domain.model.Transaction
import io.github.hyungkishin.transentia.shared.error.CommonError
import io.github.hyungkishin.transentia.shared.error.DomainException
import io.github.hyungkishin.transentia.shared.snowflake.IdGenerator
import io.github.hyungkishin.transentia.shared.snowflake.TransferId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransferService(
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val idGenerator: IdGenerator
) : TransferService {

    @Transactional
    override fun createTransfer(command: TransferRequestCommand): TransferResponseCommand {
        val sender = accountBalanceRepository.findByUserId(command.senderUserId())
            ?: throw DomainException(
                // data class 생성자로 인스턴스 생성해서 넘김
                CommonError.NotFound(
                    resource = "account_balance",
                    id = command.senderUserId().toString()
                ),
                detail = "송신자 계좌 정보를 찾을 수 없습니다. userId=${command.senderUserId()}"
            )

        val receiver = accountBalanceRepository.findByUserId(command.receiverUserId())
            ?: throw DomainException(
                CommonError.NotFound(
                    resource = "account_balance",
                    id = command.receiverUserId().toString()
                ),
                detail = "수신자 계좌 정보를 찾을 수 없습니다. userId=${command.receiverUserId()}"
            )

        val amount = command.money()

        // 검사는 canWithdraw로만, 실제 차감은 한 번만
        sender.withdrawOrThrow(amount)
        receiver.deposit(amount)

        val transaction = Transaction.of(
            transactionId = TransferId(idGenerator.nextId()), // 엇 ... 여기 여기가 애매하네
            senderUserId = command.senderUserId(),
            receiverUserId = command.receiverUserId(),
            amount = amount
        )

        accountBalanceRepository.save(sender)
        accountBalanceRepository.save(receiver)

        transaction.complete()
        val tx = transactionRepository.save(transaction)
        return TransferResponseCommand.from(tx)
    }
}
