new/post:
	hugo new posts/my-first-post.md

serve/drafts:
	hugo server --buildDrafts --source ./quickstart/
publish:
	hugo --source ./quickstart/
	cp -r ./quickstart/public/ ./docs

