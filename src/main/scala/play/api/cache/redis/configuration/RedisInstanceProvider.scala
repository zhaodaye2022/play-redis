package play.api.cache.redis.configuration

import com.typesafe.config.Config
import play.api.cache.redis._

import java.net.InetAddress

trait RedisInstanceResolver {
  def resolve: PartialFunction[String, RedisInstance]
}

sealed trait RedisInstanceProvider extends Any {
  def name: String
  def resolved(implicit resolver: RedisInstanceResolver): RedisInstance
}

final class ResolvedRedisInstance(val instance: RedisInstance) extends RedisInstanceProvider {
  override def name: String = instance.name
  override def resolved(implicit resolver: RedisInstanceResolver): RedisInstance = instance

  // $COVERAGE-OFF$
  override def equals(obj: scala.Any): Boolean = obj match {
    case that: ResolvedRedisInstance => this.name === that.name && this.instance === that.instance
    case _                           => false
  }

  override def hashCode(): Int = name.hashCode

  override def toString: String = s"ResolvedRedisInstance($name@$instance)"
  // $COVERAGE-ON$
}

final class UnresolvedRedisInstance(val name: String) extends RedisInstanceProvider {
  override def resolved(implicit resolver: RedisInstanceResolver): RedisInstance = resolver resolve name

  // $COVERAGE-OFF$
  override def equals(obj: scala.Any): Boolean = obj match {
    case that: UnresolvedRedisInstance => this.name === that.name
    case _                             => false
  }

  override def hashCode(): Int = name.hashCode

  override def toString: String = s"UnresolvedRedisInstance($name)"
  // $COVERAGE-ON$
}

private[configuration] object RedisInstanceProvider extends RedisConfigInstanceLoader[RedisInstanceProvider] {
  import RedisConfigLoader._

  override def load(config: Config, path: String, name: String)(implicit defaults: RedisSettings): RedisInstanceProvider = {
    config.getOption(path / "source", _.getString).getOrElse(defaults.source) match {
      // required static configuration of the standalone instance using application.conf
      case "standalone"        => RedisInstanceStandalone
      // required static configuration of the cluster using application.conf
      case "cluster"           => RedisInstanceCluster
      // required static configuration of the cluster using application.conf
      case "aws-cluster"       => RedisInstanceAwsCluster
      // required static configuration of the sentinel using application.conf
      case "sentinel"          => RedisInstanceSentinel
      // required static configuration of the master-slaves using application.conf
      case "master-slaves"     => RedisInstanceMasterSlaves
      // required possibly environmental configuration of the standalone instance
      case "connection-string" => RedisInstanceEnvironmental
      // supplied custom configuration
      case "custom"            => RedisInstanceCustom
      // found but unrecognized
      case other               =>
        invalidConfiguration(
          s"""
             |Unrecognized configuration provider '$other' in ${config.getValue(path / "source").origin().filename()}
             |at ${config.getValue(path / "source").origin().lineNumber()}.
             |Expected values are 'standalone', 'cluster', 'connection-string', and 'custom'.
        """.stripMargin,
        )
    }
  }.load(config, path, name)

}

/** Statically configured single standalone redis instance */
private[configuration] object RedisInstanceStandalone extends RedisConfigInstanceLoader[RedisInstanceProvider] {

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new ResolvedRedisInstance(
      RedisStandalone.apply(
        name = instanceName,
        host = RedisHost.load(config, path),
        settings = RedisSettings.withFallback(defaults).load(config, path),
      ),
    )

}

/** Statically configured redis cluster */
private[configuration] object RedisInstanceCluster extends RedisConfigInstanceLoader[RedisInstanceProvider] {

  import JavaCompatibilityBase._
  import RedisConfigLoader._

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new ResolvedRedisInstance(
      RedisCluster.apply(
        name = instanceName,
        nodes = config.getConfigList(path / "cluster").asScala.map(config => RedisHost.load(config)).toList,
        settings = RedisSettings.withFallback(defaults).load(config, path),
      ),
    )

}

/** Statically configured redis cluster driven by DNS configuration */
private[configuration] object RedisInstanceAwsCluster extends RedisConfigInstanceLoader[RedisInstanceProvider] {
  import RedisConfigLoader._

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new ResolvedRedisInstance(
      RedisCluster.apply(
        name = instanceName,
        nodes = InetAddress.getAllByName(config.getString(path / "host")).map(address => RedisHost(address.getHostAddress, 6379)).toList,
        settings = RedisSettings.withFallback(defaults).load(config, path),
      ),
    )

}

/**
  * Reads a configuration from the connection string, possibly from an
  * environmental variable. This instance configuration is designed to work in
  * PaaS environments such as Heroku.
  */
private[configuration] object RedisInstanceEnvironmental extends RedisConfigInstanceLoader[RedisInstanceProvider] {
  import RedisConfigLoader._

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new ResolvedRedisInstance(
      RedisStandalone.apply(
        name = instanceName,
        host = RedisHost.fromConnectionString(config getString path./("connection-string")),
        settings = RedisSettings.withFallback(defaults).load(config, path),
      ),
    )

}

/** Statically configures redis sentinel */
private[configuration] object RedisInstanceSentinel extends RedisConfigInstanceLoader[RedisInstanceProvider] {

  import JavaCompatibilityBase._
  import RedisConfigLoader._

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new ResolvedRedisInstance(
      RedisSentinel.apply(
        name = instanceName,
        sentinels = config.getConfigList(path / "sentinels").asScala.map(config => RedisHost.load(config)).toList,
        masterGroup = config.getString(path / "master-group"),
        password = config.getOption(path / "password", _.getString),
        database = config.getOption(path / "database", _.getInt),
        settings = RedisSettings.withFallback(defaults).load(config, path),
      ),
    )

}

/** Statically configures redis master-slaves. */
private[configuration] object RedisInstanceMasterSlaves extends RedisConfigInstanceLoader[RedisInstanceProvider] {

  import JavaCompatibilityBase._
  import RedisConfigLoader._

  def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) = new ResolvedRedisInstance(
    RedisMasterSlaves.apply(
      name = instanceName,
      master = RedisHost.load(config.getConfig(path / "master")),
      slaves = config.getConfigList(path / "slaves").asScala.map(config => RedisHost.load(config)).toList,
      username = config.getOption(path / "username", _.getString),
      password = config.getOption(path / "password", _.getString),
      database = config.getOption(path / "database", _.getInt),
      settings = RedisSettings.withFallback(defaults).load(config, path),
    ),
  )

}

/**
  * This binder indicates that the user provides his own configuration of this
  * named cache.
  */
private[configuration] object RedisInstanceCustom extends RedisConfigInstanceLoader[RedisInstanceProvider] {

  override def load(config: Config, path: String, instanceName: String)(implicit defaults: RedisSettings) =
    new UnresolvedRedisInstance(
      name = instanceName,
    )

}
