#!/usr/bin/python

# Downloads the releases repo from s3 to the local disk.
# 

import sys
import re
import os

if len(sys.argv) != 3:
    print "Usage:", sys.argv[0], "local-repo-path filelist"
    print "filelist should be the output of: aws s3 ls s3://releases.clojars.org --recursive"
    sys.exit(1)

_, local_repo, filelist_file = sys.argv

remote_url = "http://releases.clojars.org/"

file_re = re.compile("repo/.*$")

with open(filelist_file) as f:
    for line in f:
        m = file_re.findall(line)
        if m:
            f = m[0]
            local_path = local_repo + f[4:]
            if not f.endswith("/"):
                if not os.path.exists(local_path):
                    cmd = "curl --progress-bar --create-dirs -o " + local_path + " " + remote_url + f
                    print cmd
                    os.system(cmd)
                else:
                    print local_path, "already exists"
        else:
            print "Ignoring line", line

        
        
            
