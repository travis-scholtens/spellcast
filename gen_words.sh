echo "$2 = {"
cat "$1" | sed -e 's/^\(.*\)$/  "\1": 1,/'
echo '};'
