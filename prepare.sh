#!/usr/bin/env bash
docker run --name fiatbot-mongodb -v $HOME/data/fiatBot:/data/db -p 27017:27017 -d mongo
