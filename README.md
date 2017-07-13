[![ATown Data](/img/trawler-banner.png)](https://www.atowndata.com)


**Trawler** is a **real-time log aggregation solution** that collects logs from servers and indexes them in [Elasticsearch](https://github.com/elastic/elasticsearch). It is commonly used with [Kibana](https://github.com/elastic/kibana) for log exploration and [Towncrier](https://github.com/atowndata/towncrier) for alerting.

Key reasons to use **Trawler** include:
* **Reliable delivery** - Trawler supports __exactly-once guarantees__ (all log events are always delivered once and only once)
* **Horizontal scalability** - Trawler's architecture enables __high-availability__ deployments
* **High throughput** - Trawler can handle __thousands of log events__ per second across many servers

![Standard Deployment](/img/trawler-diagram.png)

## Supported Operating Systems

Trawler supports **Debian 8+** and **Ubuntu 16.04+**. CentOS and RedHat 7+ support is coming soon.

## Prerequisites

* **Redis v3.0+** - an instance of Redis that serves buffer for the log events before they're processesd
* **Elasticsearch v5.0+** - an instance (or instances) of Elasticsearch where all log events should be stored
* **Java v1.8+**

---

# Installation

To get Trawler up and running for a deployment of servers, we need to:
* [Install `trawler-shipper`](#install-trawler-shipper) on all the servers we want to aggregate log events from
* [Install `trawler-connector`](#install-trawler-connector) on a server so it can process all the log events and put them in Elasticsearch

## Install Trawler Shipper

### Ensure the right version of Java is installed:

```
java -version
```

If you receive a `java command not found` response, or the version is older than 1.8, consult [this walkthrough on how to install and use a recent version of Java](https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04).

### Download and Verify the Debian Package

Download the package and its MD5 file

```Shell
wget https://s3.amazonaws.com/trawler/trawler-shipper_0.0.1_all.deb 
wget https://s3.amazonaws.com/trawler/trawler-shipper_0.0.1_all.deb.md5
```

Verify the package with the MD5 file

````
md5sum -c trawler-shipper_0.0.1_all.deb.md5
````

The above command should output the name of the deb package file along with "OK". If it does not, delete and re-download the files.

Next, use `dpkg` to install the Debian package.

```
sudo dpkg -i trawler-shipper_0.0.1_all.deb
```

When the above command finishes, `trawler-shipper` will be installed and running on your system. Check its status:

```
sudo service trawler-shipper status
```

### Configure `trawler-shipper`

Let's configure `trawler-shipper` to point at our instance of Redis and ship log events from our log files. Open `/etc/trawler-shipper/trawler-shipper.yml`. It will contain the following:

```YAML
settings:
  localstorePath: /etc/trawler-shipper/trawler-local-db
redis:
  host: 127.0.0.1
  port: 6379
  queue: trawler-shipper-queue-1
  pollMilliseconds: 5000
services:
  # - filepath: "/example/path/logfile.log"
  #   service: example1
  #   multilinePattern: "^\\S.+"
```

#### Update Redis information

Update the Redis configuration with the correct host address (under `redis.host`) and port (under `redis.port`). 

The `redis.queue` is the name of the queue in Redis that the information is written to -- you should change this to something more descriptive. **The queue name MUST NOT have any spaces.**

#### Tell `trawler-shipper` to Ship a Logfile

For each logfile we want `trawler-shipper` to track, we have to provide:
* **filepath** - an absolute path to the logfile on the filesystem
* **service** - a string value to attach to all log events that come from this logfile (to help with exploring logs in Elasticsearch)
* **multilinePattern** - a Java regex that tells `trawler-shipper` how to break up the log events. Almost always, you'll want to use a value of `"^\\S.+"`. This tells the shipper:
    * If there's a newline with no space at the beginning, treat it as a new log event
    * If there's a newline with space at the beginning, treat it as part of the previous log event. That way, if there's an error and the log has a stacktrace, it's all included as a single event.

Additionally, we need to make sure the `trawler` user has permission to read the logfile.

Let's say we have Apache installed on a server and we want to ship the Apache access logs. We'd add a config under `services`:

```YAML
services:
  - filepath: "/var/log/apache2/access.log"
    service: apache2
    multilinePattern: "^\\S.+"
```

Next, we ensure that the `trawler` user has permission to read the log file and everything else in the `/var/log/apache2` directory. We check the permissions of everything contained in the directory:

```Shell
$ ls -l
-rw-r----- 1 root adm  45373 Jul 13 12:28 access.log
-rw-r----- 1 root adm  45373 Jul 13 12:28 error.log
-rw-r----- 1 root adm  45373 Jul 13 12:28 other_vhosts_access.log
```

We see that all the files belong to the group `adm`, which has read permissions on all the log files. So, let's check to ensure the `trawler` user belongs to the group `adm`.

```Shell
$ groups trawler
trawler : trawler adm
```

We see that the `trawler` user already belongs to the `adm` group, so it already has the permissions it needs. If it didn't, we would add `adm` as a supplementary group:

```Shell
$ usermod -a -G adm trawler
``` 

### Reload `trawler-shipper`

Finally, let's reload `trawler-shipper` to apply our new configuration.

```Shell
sudo service trawler-shipper reload
```

### Troubleshooting

If you encounter issues with getting `trawler-shipper` running, please check `/var/log/trawler-shipper/trawler-shipper.log`. All errors and warning will be outputted there.

---

## Install Trawler Connector

### Ensure the right version of Java is installed:

```
java -version
```

If you receive a `java command not found` response, or the version is older than 1.8, consult [this walkthrough on how to install and use a recent version of Java](https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04).

### Download and Verify the Debian Package

Download the package and its MD5 file

```Shell
wget https://s3.amazonaws.com/trawler/trawler-connector_0.0.1_all.deb
wget https://s3.amazonaws.com/trawler/trawler-connector_0.0.1_all.deb.md5
```

Verify the package with the MD5 file

````
md5sum -c trawler-connector_0.0.1_all.deb.md5
````

The above command should output the name of the deb package file along with "OK". If it does not, delete and re-download the files.

Next, use `dpkg` to install the Debian package.

```
sudo dpkg -i trawler-connector_0.0.1_all.deb
```

When the above command finishes, `trawler-connector` will be installed and running on your system. Check its status:

```
sudo service trawler-connector status
```

### Configure `trawler-connector`

Let's configure `trawler-connector` to correctly pull log events from the queue on Redis and index them in Elasticsearch. Open `/etc/trawler-connector/trawler-connector.yml`. It will contain the following:

```YAML
redis:
  host: 127.0.0.1
  port: 6379
  queues:
    - "trawler-shipper-queue-1"
elasticsearch:
  hosts:
    - host: 127.0.0.1
      port: 9200
      protocol: http
  indexPrefix: trawler
```

#### Update Redis information

Update the Redis configuration with the correct host address (under `redis.host`) and port (under `redis.port`). 

The `redis.queue` is the name of the queue in Redis where the log events are. **The queue name MUST MATCH the `redis.queue` specified in the `trawler-shipper` config.**

#### Update Elasticsearch information

`trawler-connector` can send log events to multiple instances of Elasticsearch. For each, specify the correct host address (under `elasticsearch.hosts[n].hosts`) and the correct port (under `elasticsearch.hosts[n].port`). 

The `elasticsearch.indexPrefix` is the name of the index in Elasticsearch that contains the log events. You can change it or leave it as "trawler". **The `indexPrefix` MUST NOT have any spaces.**

### Reload `trawler-connector`

Finally, let's reload `trawler-connector` to apply our new configuration.

```Shell
sudo service trawler-connector reload
```

### Troubleshooting

If you encounter issues with getting `trawler-connector` running, please check `/var/log/trawler-connector/trawler-connector.log`. All errors and warning will be outputted there.

---

## Contacting Us / Contributions

Please use the [Github Issues](https://github.com/atowndata/trawler/issues) page for questions, ideas and bug reports. Pull requests are welcome.

Trawler was built by the consulting team at [ATown Data](https://www.atowndata.com). Please [contact us](https://atowndata.com/contact/) if you have a project you'd like to talk to us about!


## License

Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
Copyright &copy; 2017 [ATown Data](https://www.atowndata.com)