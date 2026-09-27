#!/bin/bash

# Gets a snapshot from git rep into the current directoryo


( cd $HOME/workspaces/loanstreet; git archive --format=tar 240bd2eee118c9da40eb60bd3eb9163d2e3c7792 ) | tar -xv

# Remove test classes relating to BuyerHealthAggregateService
#find . -name BuyerHealthAggregateService\*Test.kt | xargs rm

# Remove test classes relating to the CurrentDistressAggregateService
#find . -name CurrentDistressAggregateService\*Test.kt | xargs rm
