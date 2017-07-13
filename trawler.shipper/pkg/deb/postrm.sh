#!/bin/sh -e

if [ "$1" = "purge" ] ; then
  update-rc.d trawler-shipper remove >/dev/null
fi
