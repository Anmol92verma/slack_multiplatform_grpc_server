package dev.baseio.slackserver.data.sources

import dev.baseio.slackserver.data.models.SkUser


interface AuthDataSource {
  suspend fun register(email: String, user: SkUser): SkUser?
  suspend fun sendEmailLink(email: String, workspaceId: String): SkUser?
}

