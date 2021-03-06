package com.socrata.datacoordinator.service

import com.typesafe.config.Config

class AdvertisementConfig(config: Config) {
  val basePath = config.getString("base-path")
  val name = config.getString("name")
  val address = config.getString("address")
  val instance = config.getInt("instance")
}
