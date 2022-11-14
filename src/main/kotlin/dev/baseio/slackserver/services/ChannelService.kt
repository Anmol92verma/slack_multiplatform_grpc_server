package dev.baseio.slackserver.services

import dev.baseio.slackdata.common.sKByteArrayElement
import dev.baseio.slackdata.protos.*
import dev.baseio.slackserver.communications.NotificationType
import dev.baseio.slackserver.communications.PNSender
import dev.baseio.slackserver.data.models.SKUserPublicKey
import dev.baseio.slackserver.data.sources.ChannelsDataSource
import dev.baseio.slackserver.data.models.SkChannel
import dev.baseio.slackserver.data.models.SkChannelMember
import dev.baseio.slackserver.data.sources.ChannelMemberDataSource
import dev.baseio.slackserver.data.sources.UsersDataSource
import dev.baseio.slackserver.security.*
import dev.baseio.slackserver.services.interceptors.AUTH_CONTEXT_KEY
import dev.baseio.slackserver.services.interceptors.AuthData
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.PublicKey
import java.util.*
import kotlin.coroutines.CoroutineContext

class ChannelService(
    coroutineContext: CoroutineContext = Dispatchers.IO,
    private val channelsDataSource: ChannelsDataSource,
    private val channelMemberDataSource: ChannelMemberDataSource,
    private val usersDataSource: UsersDataSource,
    private val channelPNSender: PNSender<SkChannel>,
    private val channelMemberPNSender: PNSender<SkChannelMember>,
) :
    ChannelsServiceGrpcKt.ChannelsServiceCoroutineImplBase(coroutineContext) {

    override suspend fun inviteUserToChannel(request: SKInviteUserChannel): SKChannelMembers {
        val userData = AUTH_CONTEXT_KEY.get()
        return inviteUserWithAuthData(request, userData)
    }


    override suspend fun joinChannel(request: SKChannelMember): SKChannelMember {
        channelMemberDataSource.addMembers(listOf(request.toDBMember()))
        return request
    }

    override suspend fun channelMembers(request: SKWorkspaceChannelRequest): SKChannelMembers {
        return channelMemberDataSource.getMembers(request.workspaceId, request.channelId).run {
            SKChannelMembers.newBuilder()
                .addAllMembers(this.map { it.toGRPC() })
                .build()
        }
    }

    override suspend fun getAllChannels(request: SKChannelRequest): SKChannels {
        val userData = AUTH_CONTEXT_KEY.get()
        return channelsDataSource.getAllChannels(request.workspaceId, userData.userId).run {
            SKChannels.newBuilder()
                .addAllChannels(this.map { it.toGRPC() })
                .build()
        }
    }

    override suspend fun getAllDMChannels(request: SKChannelRequest): SKDMChannels {
        val userData = AUTH_CONTEXT_KEY.get()
        return channelsDataSource.getAllDMChannels(request.workspaceId, userData.userId).run {
            SKDMChannels.newBuilder()
                .addAllChannels(this.map { it.toGRPC() })
                .build()
        }
    }

    override suspend fun savePublicChannel(request: SKChannel): SKChannel {
        val authData = AUTH_CONTEXT_KEY.get()
        val previousChannelExists = channelsDataSource.checkIfGroupExisits(request.workspaceId, request.name)
        if (previousChannelExists) {
            throw StatusException(Status.ALREADY_EXISTS)
        }

        val channel = request.toDBChannel()
        val keyManager = RsaEcdsaKeyManager(channel.uuid)
        val publicKeyChannel = keyManager.getPublicKey().encoded
        val saved = channelsDataSource.savePublicChannel(
            channel.copy(channelPublicKey = SKUserPublicKey(publicKeyChannel)),
            adminId = authData.userId
        )?.toGRPC()
        channelPNSender.sendPushNotifications(
            channel,
            authData.userId,
            NotificationType.CHANNEL_CREATED
        )
        inviteUserWithAuthData(sKInviteUserChannel {
            this.channelId = channel.uuid
            this.userId = authData.userId
            this.channelPrivateKey =
                keyManager.getPrivateKey().encoded.encryptWithUserPublicKey(
                    usersDataSource.getUser(
                        authData.userId,
                        request.workspaceId
                    )!!.publicKey
                )
        }, authData)
        keyManager.rawDeleteKeyPair()
        return saved ?: throw StatusException(Status.NOT_FOUND)
    }

    override suspend fun saveDMChannel(request: SKDMChannel): SKDMChannel {
        val authData = AUTH_CONTEXT_KEY.get()
        val previousChannel = channelsDataSource.checkIfDMChannelExists(request.senderId, request.receiverId)
        previousChannel?.let {
            return it.toGRPC()
        } ?: kotlin.run {
            val keyManager = RsaEcdsaKeyManager(request.uuid)

            val publicKeyChannel = keyManager.getPublicKey().encoded
            val channel = dbChannel(request, publicKeyChannel)
            val savedChannel = channelsDataSource.saveDMChannel(channel)?.toGRPC()!!
            channelPNSender.sendPushNotifications(
                channel,
                authData.userId,
                NotificationType.DM_CHANNEL_CREATED
            )
            inviteUserWithAuthData(sKInviteUserChannel {
                this.channelId = savedChannel.uuid
                this.userId = request.senderId
                this.channelPrivateKey =
                    keyManager.getPrivateKey().encoded.encryptWithUserPublicKey(
                        usersDataSource.getUser(
                            request.senderId,
                            request.workspaceId
                        )!!.publicKey
                    )
            }, authData)
            inviteUserWithAuthData(sKInviteUserChannel {
                this.channelId = savedChannel.uuid
                this.userId = request.receiverId
                this.channelPrivateKey =
                    keyManager.getPrivateKey().encoded.encryptWithUserPublicKey(
                        usersDataSource.getUser(
                            request.receiverId,
                            request.workspaceId
                        )!!.publicKey
                    )
            }, authData)
            keyManager.rawDeleteKeyPair()
            return savedChannel
        }
    }

    private suspend fun inviteUserWithAuthData(
        request: SKInviteUserChannel,
        userData: AuthData
    ): SKChannelMembers {
        val user = usersDataSource.getUserWithUsername(userName = request.userId, userData.workspaceId)
            ?: usersDataSource.getUserWithUserId(userId = request.userId, userData.workspaceId)
        val channel =
            channelsDataSource.getChannelById(request.channelId, userData.workspaceId)
                ?: channelsDataSource.getChannelByName(
                    request.channelId,
                    userData.workspaceId
                )
        user?.let { safeUser ->
            channel?.let { channel ->
                joinChannel(sKChannelMember {
                    this.channelId = channel.channelId
                    this.memberId = safeUser.uuid
                    this.workspaceId = userData.workspaceId
                    this.channelPrivateKey = request.channelPrivateKey
                }.also {
                    channelMemberPNSender.sendPushNotifications(
                        it.toDBMember(),
                        userData.userId,
                        NotificationType.ADDED_CHANNEL
                    )
                })

                return channelMembers(sKWorkspaceChannelRequest {
                    this.channelId = channel.channelId
                    this.workspaceId = userData.workspaceId
                })
            } ?: run {
                throw StatusException(Status.NOT_FOUND)
            }

        } ?: run {
            throw StatusException(Status.NOT_FOUND)
        }
    }

    private fun dbChannel(
        request: SKDMChannel,
        publicKeyChannel: ByteArray
    ): SkChannel.SkDMChannel {
        val channel = request.copy {
            uuid = request.uuid.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            createdDate = System.currentTimeMillis()
            modifiedDate = System.currentTimeMillis()
            publicKey = slackPublicKey {
                this.keybytes.addAll(publicKeyChannel.map {
                    sKByteArrayElement {
                        this.byte = it.toInt()
                    }
                })
            }
        }.toDBChannel()
        return channel
    }

    override fun registerChangeInChannelMembers(request: SKChannelMember): Flow<SKChannelMemberChangeSnapshot> {
        return channelsDataSource.getChannelMemberChangeStream(request.workspaceId, request.memberId).map { skChannel ->
            SKChannelMemberChangeSnapshot.newBuilder()
                .apply {
                    skChannel.first?.toGRPC()?.let { skChannel1 ->
                        previous = skChannel1
                    }
                    skChannel.second?.toGRPC()?.let { skChannel1 ->
                        latest = skChannel1
                    }
                }
                .build()
        }
    }

    override fun registerChangeInChannels(request: SKChannelRequest): Flow<SKChannelChangeSnapshot> {
        val authData = AUTH_CONTEXT_KEY.get()
        return channelsDataSource.getChannelChangeStream(request.workspaceId).map { skChannel ->
            SKChannelChangeSnapshot.newBuilder()
                .apply {
                    skChannel.first?.toGRPC()?.let { skChannel1 ->
                        val isMember =
                            channelMemberDataSource.isMember(authData.userId, request.workspaceId, skChannel1.uuid)
                        if (isMember) {
                            previous = skChannel1
                        }
                    }
                    skChannel.second?.toGRPC()?.let { skChannel1 ->
                        val isMember =
                            channelMemberDataSource.isMember(authData.userId, request.workspaceId, skChannel1.uuid)
                        if (isMember) {
                            latest = skChannel1
                        }
                    }
                }
                .build()
        }
    }

    override fun registerChangeInDMChannels(request: SKChannelRequest): Flow<SKDMChannelChangeSnapshot> {
        return channelsDataSource.getDMChannelChangeStream(request.workspaceId).map { skChannel ->
            SKDMChannelChangeSnapshot.newBuilder()
                .apply {
                    skChannel.first?.toGRPC()?.let { skMessage ->
                        previous = skMessage
                    }
                    skChannel.second?.toGRPC()?.let { skMessage ->
                        latest = skMessage
                    }
                }
                .build()
        }
    }
}

fun ByteArray.encryptWithUserPublicKey(publicKey: SKUserPublicKey): SlackPublicKey {
    return HybridRsaUtils.encrypt(
        this,
        publicKey.toPublicKey(),
        RsaEcdsaConstants.Padding.OAEP,
        RsaEcdsaConstants.OAEP_PARAMETER_SPEC
    ).toSlackPublicKey()
}

private fun ByteArray.toSlackPublicKey(): SlackPublicKey {
    return slackPublicKey {
        this.keybytes.addAll(this@toSlackPublicKey.map {
            sKByteArrayElement {
                byte = it.toInt()
            }
        })
    }
}

fun SKUserPublicKey.toPublicKey(): PublicKey {
    return JVMKeyStoreRsaUtils.getPublicKeyFromBytes(this.keyBytes)
}

private fun SKChannelMember.toDBMember(): SkChannelMember {
    return SkChannelMember(
        this.workspaceId,
        this.channelId,
        this.memberId,
        this.channelPrivateKey.toSKUserPublicKey()
    ).apply {
        this@toDBMember.uuid?.takeIf { it.isNotEmpty() }?.let {
            this.uuid = this@toDBMember.uuid
        }
    }
}

private fun SlackPublicKey.toSKUserPublicKey(): SKUserPublicKey {
    return SKUserPublicKey(this.keybytesList.map { it.byte.toByte() }.toByteArray())
}

fun SkChannelMember.toGRPC(): SKChannelMember {
    val member = this
    return sKChannelMember {
        this.uuid = member.uuid
        this.channelId = member.channelId
        this.workspaceId = member.workspaceId
        this.memberId = member.memberId
        this.channelPrivateKey = slackPublicKey {
            this.keybytes.addAll(member.channelEncryptedPrivateKey!!.keyBytes.map {
                sKByteArrayElement {
                    byte = it.toInt()
                }
            })
        }
    }
}

fun SKDMChannel.toDBChannel(
): SkChannel.SkDMChannel {
    return SkChannel.SkDMChannel(
        this.uuid,
        this.workspaceId,
        this.senderId,
        this.receiverId,
        createdDate,
        modifiedDate,
        isDeleted,
        SKUserPublicKey(keyBytes = this.publicKey.keybytesList.map { it.byte.toByte() }.toByteArray())
    )
}

fun SKChannel.toDBChannel(
    channelId: String = UUID.randomUUID().toString()
): SkChannel.SkGroupChannel {
    return SkChannel.SkGroupChannel(
        this.uuid.takeIf { !it.isNullOrEmpty() } ?: channelId,
        this.workspaceId,
        this.name,
        createdDate,
        modifiedDate,
        avatarUrl,
        isDeleted,
        SKUserPublicKey(keyBytes = this.publicKey.keybytesList.map { it.byte.toByte() }.toByteArray())
    )
}

fun SkChannel.SkGroupChannel.toGRPC(): SKChannel {
    return SKChannel.newBuilder()
        .setUuid(this.uuid)
        .setAvatarUrl(this.avatarUrl ?: "")
        .setName(this.name)
        .setCreatedDate(this.createdDate)
        .setWorkspaceId(this.workspaceId)
        .setModifiedDate(this.modifiedDate)
        .setPublicKey(SlackPublicKey.newBuilder().addAllKeybytes(this.publicKey.keyBytes.map {
            sKByteArrayElement {
                this.byte = it.toInt()
            }
        }).build())
        .build()
}

fun SkChannel.SkDMChannel.toGRPC(): SKDMChannel {
    return SKDMChannel.newBuilder()
        .setUuid(this.uuid)
        .setCreatedDate(this.createdDate)
        .setModifiedDate(this.modifiedDate)
        .setIsDeleted(this.deleted)
        .setReceiverId(this.receiverId)
        .setSenderId(this.senderId)
        .setWorkspaceId(this.workspaceId)
        .setPublicKey(SlackPublicKey.newBuilder().addAllKeybytes(this.publicKey.keyBytes.map {
            sKByteArrayElement {
                this.byte = it.toInt()
            }
        }).build())
        .build()
}
