#!/bin/sh -e
# Fakeroot and lein don't get along, so we set ownership after the fact.
chown -R root:root /usr/share/trawler-shipper
chown root:root /usr/bin/trawler-shipper
chown trawler:trawler /var/log/trawler-shipper
chown -R trawler:trawler /etc/trawler-shipper
chown root:root /etc/init.d/trawler-shipper
chown root:root /etc/default/trawler-shipper

# Start trawler-shipper on boot
if [ -x "/etc/init.d/trawler-shipper" ]; then
  if [ ! -e "/etc/init/trawler-shipper.yml" ]; then
    update-rc.d trawler-shipper defaults >/dev/null
  fi
fi

invoke-rc.d trawler-shipper start
