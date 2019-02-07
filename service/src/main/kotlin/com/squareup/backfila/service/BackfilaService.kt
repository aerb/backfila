package com.squareup.backfila.service

import com.squareup.skim.ServiceBuilder
import misk.inject.KAbstractModule

fun main(args: Array<String>) {
  ServiceBuilder.getMiskApplication(::applicationModules).run(args)
}

fun applicationModules(serviceBuilder: ServiceBuilder<BackfilaConfig>): List<KAbstractModule> {
  return listOf(BackfilaServiceModule())
}