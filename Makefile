a.out.js: rot.js app.js experience.js foes.js words.js
	closure-compiler --js rot.js --js app.js --js experience.js --js foes.js --js words.js --language_in ECMASCRIPT5 --js_output_file a.out.js
