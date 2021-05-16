/*
 * Copyright (c) 2020 Dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.panda_lang.reposilite.console.infrastructure

import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.plugin.openapi.annotations.HttpMethod.POST
import io.javalin.plugin.openapi.annotations.OpenApi
import org.panda_lang.reposilite.ReposiliteContextFactory
import io.javalin.plugin.openapi.annotations.OpenApiParam
import io.javalin.plugin.openapi.annotations.OpenApiResponse
import io.javalin.plugin.openapi.annotations.OpenApiContent
import org.apache.http.HttpStatus
import org.panda_lang.reposilite.console.api.RemoteExecutionResponse
import org.panda_lang.reposilite.failure.api.ErrorResponse
import org.panda_lang.reposilite.auth.Authenticator
import org.panda_lang.reposilite.auth.Session
import org.panda_lang.reposilite.console.Console
import org.panda_lang.reposilite.failure.ResponseUtils
import org.panda_lang.utilities.commons.StringUtils
import org.panda_lang.utilities.commons.function.Result

internal class RemoteExecutionEndpoint(
    private val contextFactory: ReposiliteContextFactory,
    private val console: Console
) : Handler {

    companion object {
        private const val MAX_COMMAND_LENGTH = 1024
    }

    @OpenApi(
        operationId = "cli",
        method = POST,
        summary = "Remote command execution",
        description = "Execute command using POST request. The commands are the same as in the console and can be listed using the 'help' command.",
        tags = ["Cli"],
        headers = [OpenApiParam(name = "Authorization", description = "Alias and token provided as basic auth credentials", required = true)],
        responses = [OpenApiResponse(
            status = "200",
            description = "Status of the executed command",
            content = [OpenApiContent(from = RemoteExecutionResponse::class)]
        ), OpenApiResponse(
            status = "400",
            description = "Error message related to the invalid command format (0 < command length < " + MAX_COMMAND_LENGTH + ")",
            content = [OpenApiContent(from = ErrorResponse::class)]
        ), OpenApiResponse(
            status = "401",
            description = "Error message related to the unauthorized access",
            content = [OpenApiContent(from = ErrorResponse::class)]
        )]
    )
    override fun handle(ctx: Context) {
        val context = contextFactory.create(ctx)
        console.logger.info("REMOTE EXECUTION ${context.uri} from ${context.address}")

        val authResult: Result<Session, String> = authenticator.authByHeader(context.headers)

        if (authResult.isErr) {
            ResponseUtils.errorResponse(ctx, HttpStatus.SC_UNAUTHORIZED, authResult.error)
            return
        }

        val session = authResult.get()

        if (!session.isManager()) {
            ResponseUtils.errorResponse(ctx, HttpStatus.SC_UNAUTHORIZED, "Authenticated user is not a manger")
            return
        }

        val command = ctx.body()

        if (StringUtils.isEmpty(command)) {
            ResponseUtils.errorResponse(ctx, HttpStatus.SC_BAD_REQUEST, "Missing command")
            return
        }

        if (command.length > MAX_COMMAND_LENGTH) {
            ResponseUtils.errorResponse(
                ctx,
                HttpStatus.SC_BAD_REQUEST,
                "The given command exceeds allowed length (${command.length} > $MAX_COMMAND_LENGTH)"
            )

            return
        }

        console.logger.info("${session.accessToken.alias} (${context.address}) requested command: $command")

        val result = console.execute(command)
        ctx.json(RemoteExecutionResponse(result.isOk, if (result.isOk) result.get() else result.error))
    }

}