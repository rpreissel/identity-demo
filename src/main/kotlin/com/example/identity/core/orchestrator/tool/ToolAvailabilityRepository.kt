package com.example.identity.core.orchestrator.tool

import com.example.identity.core.orchestrator.domain.ChannelType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ToolAvailabilityRepository : JpaRepository<ToolAvailability, ToolAvailabilityKey> {
    fun findByChannel(channel: ChannelType): List<ToolAvailability>
}

@Repository
interface ToolOrderRepository : JpaRepository<ToolOrder, ToolOrderKey> {
    fun findByChannel(channel: ChannelType): List<ToolOrder>
}
