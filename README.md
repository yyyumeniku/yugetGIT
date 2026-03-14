yugetGIT is a backup mod for minecraft that uses git and git lfs to manage world snapshots.

Supported version: 
- [ ] 1.20+
- [ ] 1.16.4
- [x] 1.12.2
- [ ] 1.7.10 

Stage 1:
- [x] git
- [x] git lfs
- [x] backup saves
- [x] backup restores
- [x] backup list
- [ ] backup pruning
- [x] backup when exiting the world
- [x] world repository management
- [x] debug dialog
- [ ] backup scheduling
- [ ] backup pruning based on time and count
- [ ] backup pruning based on git history graph (delete commits that are no longer ancestors of any branch tips)
- [x] git push
- [x] git pull
- [x] git fetch
- [x] git merge
- [x] git rebase
- [x] github repo creation
- [x] branch system for new worlds
- [ ] branching system for existing worlds
- [ ] automatic fetch on world start (no bossbar just background


Stage 2:
- [ ] Ui in world
- [ ] Modlist snapshot per commit


This projects has been able to be succesful because of:
* pcal43 and his mod fastback! (https://github.com/pcal43/fastback)
* Git, Git LFS, and their documentation