echo 'words = {'
cat "$1" | sed -e 's/^\(.*\)$/  \1: true,/'
echo '};'
