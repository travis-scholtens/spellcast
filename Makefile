all: a.out.js compiled_words_am.js compiled_words_nz.js index.html manifest.json icon_36.png icon_48.png icon_60.png icon_72.png icon_76.png icon_96.png icon_96.png icon_120.png icon_128.png icon_144.png icon_152.png icon_192.png
	$(eval SCRIPT_NAME=$(shell cat index.html a.out.js | md5sum | cut -d ' ' -f1))
	$(eval WORDS_AM_NAME=$(shell cat compiled_words_am.js | md5sum | cut -d ' ' -f1))
	$(eval WORDS_NZ_NAME=$(shell cat compiled_words_nz.js | md5sum | cut -d ' ' -f1))
	mkdir -p dist
	cp a.out.js dist/$(SCRIPT_NAME).js
	cp compiled_words_am.js dist/$(WORDS_AM_NAME).js
	cp compiled_words_nz.js dist/$(WORDS_NZ_NAME).js
	cp manifest.json dist/
	cp icon_*.png dist/
	cat index.html | sed -e 's/{APP}/$(SCRIPT_NAME)/' | sed -e 's/{WORDS_AM}/$(WORDS_AM_NAME)/' | sed -e 's/{WORDS_NZ}/$(WORDS_NZ_NAME)/' > dist/index.html
	cat manifest.tmpl > dist/index.appcache
	echo $(SCRIPT_NAME).js >> dist/index.appcache
	echo $(WORDS_AM_NAME).js >> dist/index.appcache
	echo $(WORDS_NZ_NAME).js >> dist/index.appcache
	echo '# $(shell date -u +%Y-%m-%d-%H-%M-%S)' >> dist/index.appcache

experience.js: bingo.py
	python3 bingo.py

foes.js: bingo.py
	python3 bingo.py

words_am.js: $(WORDS_FILE) gen_words.sh
	$(eval WORDS=$(shell mktemp))
	grep -v '^.$$' $(WORDS_FILE) | grep -v ........ | grep '^[a-m]' > $(WORDS)
	sh gen_words.sh $(WORDS) words_am > words_am.js
	rm $(WORDS)

words_nz.js: $(WORDS_FILE) gen_words.sh
	$(eval WORDS=$(shell mktemp))
	grep -v '^.$$' $(WORDS_FILE) | grep -v ........ | grep '^[n-z]' > $(WORDS)
	sh gen_words.sh $(WORDS) words_nz > words_nz.js
	rm $(WORDS)

compiled_words_am.js: words_am.js
	closure-compiler --js words_am.js --language_in ECMASCRIPT5 --js_output_file compiled_words_am.js

compiled_words_nz.js: words_nz.js
	closure-compiler --js words_nz.js --language_in ECMASCRIPT5 --js_output_file compiled_words_nz.js

a.out.js: rot.js app.js experience.js foes.js
	closure-compiler --js rot.js --js app.js --js experience.js --js foes.js --language_in ECMASCRIPT5 --js_output_file a.out.js

clean:
	rm dist/*
