import json,re
src=open('../demoyc.html').read().split('\n')
head='\n'.join(src[:68])            # through A scene close
B='\n'.join(src[68:80])            # B scene (lines 69-80)
helpers='\n'.join(src[125:196])    # $ ... renderB (without CFG line fixes)
html=open('part_html.html').read(); js=open('part_js.js').read()
routes=json.load(open('../yc/map/routes.json'))
out=head+'\n'+B+'\n'+html+'\n</div>\n<script>\nconst ROUTES='+json.dumps(routes)+';\n'+helpers+'\n'+js+'\n</script>\n</body>\n</html>\n'
out=out.replace('<title>ReliefSync — YC Pitch Film (new scenes)</title>','<title>ReliefSync — Film</title>')
open('../film.html','w').write(out)
print(len(out))
