package com.example.kotlintest.user

import com.example.kotlintest.common.PageResponse
import com.example.kotlintest.common.idempotency.IdempotentExecutor
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val idempotentExecutor: IdempotentExecutor,
) {

    /** Requires an `Idempotency-Key` header (see [IdempotentExecutor]). */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateUserRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<UserResponse> = idempotentExecutor.execute(httpRequest) {
        val user = UserResponse.from(userService.register(request.name, request.email))
        ResponseEntity.created(URI.create("/api/v1/users/${user.id}")).body(user)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): UserResponse = UserResponse.from(userService.get(id))

    @GetMapping
    fun list(pageable: Pageable): PageResponse<UserResponse> =
        PageResponse.from(userService.list(pageable), UserResponse::from)
}
