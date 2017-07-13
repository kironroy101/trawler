#!/bin/sh -e

if [ "$1" = "purge" ] ; then
  update-rc.d trawler-connector remove >/dev/null
fi
