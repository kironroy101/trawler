#!/bin/sh -e
# Fakeroot and lein don't get along, so we set ownership after the fact.
chown -R root:root /usr/share/trawler-connector
chown root:root /usr/bin/trawler-connector
chown trawler:trawler /var/log/trawler-connector
chown -R trawler:trawler /etc/trawler-connector
chown root:root /etc/init.d/trawler-connector
chown root:root /etc/default/trawler-connector

# Start trawler-connector on boot
if [ -x "/etc/init.d/trawler-connector" ]; then
  if [ ! -e "/etc/init/trawler-connector.yml" ]; then
    update-rc.d trawler-connector defaults >/dev/null
  fi
fi

invoke-rc.d trawler-connector start
