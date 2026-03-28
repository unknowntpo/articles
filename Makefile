
all: help

## help: print this help message
help:
	@echo 'Usage:'
	@sed -n 's/^##//p' ${MAKEFILE_LIST} | column -t -s ':' |  sed -e 's/^/ /'

## ---- Hugo (legacy) ----

## hugo/new/post: create a new post, use POST to specify post name
hugo/new/post:
	cd quickstart && hugo new posts/$(POST).md

## hugo/serve/drafts: preview the page in hugo server
hugo/serve/drafts:
	hugo server --buildDrafts --source ./quickstart/

## hugo/publish: copy ./quickstart/public to ./docs to allow github display it
hugo/publish:
	hugo --source ./quickstart/
	cp -r ./quickstart/public/ ./docs

.PHONY: help hugo/new/post hugo/serve/drafts hugo/publish
