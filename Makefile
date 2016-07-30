FONT_EXT=ttf

all: a.out.js compiled_words_am.js compiled_words_nz.js index.html manifest.json icon_36.png icon_48.png icon_60.png icon_72.png icon_76.png icon_96.png icon_96.png icon_120.png icon_128.png icon_144.png icon_152.png icon_192.png font.css SpellyFreeMono.$(FONT_EXT)
	$(eval SCRIPT_NAME=$(shell cat index.html a.out.js | $(MD5) | cut -d ' ' -f1))
	$(eval WORDS_AM_NAME=$(shell cat compiled_words_am.js | $(MD5) | cut -d ' ' -f1))
	$(eval WORDS_NZ_NAME=$(shell cat compiled_words_nz.js | $(MD5) | cut -d ' ' -f1))
	mkdir -p dist
	cp a.out.js dist/$(SCRIPT_NAME).js
	cp compiled_words_am.js dist/$(WORDS_AM_NAME).js
	cp compiled_words_nz.js dist/$(WORDS_NZ_NAME).js
	cp manifest.json dist/
	cp icon_*.png dist/
	cp font.css dist/
	cp SpellyFreeMono.$(FONT_EXT) dist/
	cat index.html | sed -e 's/{APP}/$(SCRIPT_NAME)/' | sed -e 's/{WORDS_AM}/$(WORDS_AM_NAME)/' | sed -e 's/{WORDS_NZ}/$(WORDS_NZ_NAME)/' | sed -e 's/{GA_TRACKING_ID}/$(GA_TRACKING_ID)/' > dist/index.html
	cat manifest.tmpl > dist/index.appcache
	echo SpellyFreeMono.$(FONT_EXT) >> dist/index.appcache
	echo $(SCRIPT_NAME).js >> dist/index.appcache
	echo $(WORDS_AM_NAME).js >> dist/index.appcache
	echo $(WORDS_NZ_NAME).js >> dist/index.appcache
	echo '# $(shell date -u +%Y-%m-%d-%H-%M-%S)' >> dist/index.appcache
	cat dist/index.appcache | sed -e 's/#.*//' | grep . | awk 'f;/^CACHE:/{f=1}' | sed -e "s/\(.*\)/    '\1',/" > urls.txt
	sed '/var urlsToCache = \[/r urls.txt' sw.js > dist/sw.js

stop_robots:
	echo 'User-agent: *' > dist/robots.txt
	echo 'Disallow: /' >> dist/robots.txt

icon_36.png: icon.svg
	inkscape -z -e $@ -w 36 $<

icon_48.png: icon.svg
	inkscape -z -e $@ -w 48 $<

icon_60.png: icon.svg
	inkscape -z -e $@ -w 60 $<

icon_72.png: icon.svg
	inkscape -z -e $@ -w 72 $<

icon_76.png: icon.svg
	inkscape -z -e $@ -w 76 $<

icon_96.png: icon.svg
	inkscape -z -e $@ -w 96 $<

icon_120.png: icon.svg
	inkscape -z -e $@ -w 120 $<

icon_128.png: icon.svg
	inkscape -z -e $@ -w 128 $<

icon_144.png: icon.svg
	inkscape -z -e $@ -w 144 $<

icon_152.png: icon.svg
	inkscape -z -e $@ -w 152 $<

icon_192.png: icon.svg
	inkscape -z -e $@ -w 192 $<

SpellyFreeMono.$(FONT_EXT): freefont/FreeMono.sfd
	FONTFORGE_LANGUAGE=pe fontforge -c 'Open($$1); Generate($$2)' $< $@

experience.js: bingo.py
	python3 bingo.py

foes.js: bingo.py
	python3 bingo.py

logo.js: logo.txt escapeuni.py
	echo "var logo = [" > $@
	python3 escapeuni.py < logo.txt >> $@
	echo "];" >> $@

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

a.out.js: rot.js/rot.js app.js experience.js foes.js logo.js
	closure-compiler --js rot.js/rot.js --js app.js --js experience.js --js foes.js --js logo.js --language_in ECMASCRIPT5 --js_output_file a.out.js

clean:
	rm dist/*
