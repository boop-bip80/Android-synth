package com.orbital.synth.core.jazz

/**
 * The catalogue data for The Real Book, Volume 1, Sixth Edition (Hal Leonard): all 400 titles,
 * in the order and under the section headings of the book's own alphabetical list. The book
 * files "(I Love You) For Sentimental Reasons" under F and "(There Is) No Greater Love" under T,
 * so the headings are data here rather than derived from the titles.
 *
 * **Provenance, so nobody mistakes this for more than it is.**
 *  - The 400 *titles* and their order come from the contents list the project owner supplied
 *    (publisher catalogue cross-checked against a second 400-title index). They are not
 *    re-derived from the book itself.
 *  - The *composer*, *home key* and *feel* columns are separate annotations added afterwards.
 *    They are filled in only where the annotator was confident, and are `?` (composer, key) or
 *    `-` (feel) otherwise. Nothing in them was read from the book, none of it has been checked
 *    against the book's charts, and some of it may be wrong. Correct a line in place; the unit
 *    tests re-validate every line.
 *
 * Line format (blank lines and `#` comments are ignored):
 *
 *     [A]                                  section heading: the book's own filing letter (`[#]` for digits)
 *     Title | Composer | key | feel [| extras]
 *
 *  - `Composer`  names, or `?` when not recorded.
 *  - `key`       `C`, `Bb`, `F#m`, `Gmix`, `Ddor`, `Clyd`, `Ephr`, `Cblues`, `Ahm`; or `?` when the
 *                home key is not recorded (the stage then keeps the player's current key).
 *  - `feel`      ballad, medium, up, blues, bossa, latin, waltz, funk, modal, odd; or `-` when not
 *                recorded (the sound then uses the neutral medium defaults and no tempo is shown).
 *  - `extras`    optional, space separated: `voice=<code>` (ep, piano, organ, guitar, nylon, bass,
 *                vibes, marimba, trumpet, sax, flute, strings, clav) and `bpm=<n>`.
 *
 * Only facts about a tune belong here. Never add melodies or chord charts: those are the
 * copyrighted content of the book. Keys and feels are suggestions for the stage, not
 * transcriptions of the book's charts.
 */
internal object RealBookVol1Data {
    const val TEXT: String = """
[#]
500 Miles High | Chick Corea | Em | latin
502 Blues | Wayne Shorter | ? | blues
[A]
African Flower (Petite Fleur Africaine) | Duke Ellington | ? | ballad
Afro Blue | Mongo Santamaría | ? | latin
Afternoon in Paris | John Lewis | C | up
Água de Beber (Water to Drink) | Antonio Carlos Jobim | ? | bossa
Airegin | Sonny Rollins | Fm | up
Alfie | Burt Bacharach | ? | ballad
Alice in Wonderland | Sammy Fain | ? | waltz
All Blues | Miles Davis | Gmix | blues | voice=trumpet
All by Myself | Irving Berlin | ? | ballad
All of Me | Gerald Marks, Seymour Simons | C | medium
All of You | Cole Porter | ? | medium
All the Things You Are | Jerome Kern | Ab | medium
Alright, Okay, You Win | Sid Wyche, Mayme Watts | ? | medium
Always | Irving Berlin | ? | waltz
Ana Maria | Wayne Shorter | ? | -
Angel Eyes | Matt Dennis | ? | ballad
Anthropology | Charlie Parker, Dizzy Gillespie | Bb | up
Apple Honey | Woody Herman | ? | up
April in Paris | Vernon Duke | ? | medium
April Joy | Pat Metheny | ? | -
Arise, Her Eyes | ? | ? | -
Armageddon | Wayne Shorter | ? | up
Au Privave | Charlie Parker | F | up
Autumn in New York | Vernon Duke | ? | ballad
Autumn Leaves | Joseph Kosma | Gm | medium
[B]
Beautiful Love | Victor Young, Wayne King, Egbert Van Alstyne | ? | medium
Beauty and the Beast | ? | ? | -
Bessie's Blues | John Coltrane | ? | blues
Bewitched | Richard Rodgers | ? | ballad
Big Nick | John Coltrane | ? | -
Black Coffee | Sonny Burke | ? | ballad
Black Diamond | ? | ? | -
Black Narcissus | Joe Henderson | ? | -
Black Nile | Wayne Shorter | ? | -
Black Orpheus | Luiz Bonfá | Am | bossa
Blue Bossa | Kenny Dorham | Cm | bossa
Blue in Green | Miles Davis | ? | ballad
Blue Monk | Thelonious Monk | Bb | blues | voice=piano
The Blue Room | Richard Rodgers | ? | medium
Blue Train (Blue Trane) | ? | ? | -
Blues for Alice | Charlie Parker | F | up
Bluesette | Toots Thielemans | ? | waltz
Body and Soul | Johnny Green | Db | ballad
Boplicity (Be Bop Lives) | Miles Davis (as Cleo Henry) | ? | medium
Bright Size Life | Pat Metheny | ? | -
Broad Way Blues | Ornette Coleman | ? | blues
Broadway | Billy Bird, Teddy McRae, Henri Woode | ? | up
But Beautiful | Jimmy Van Heusen | ? | ballad
Butterfly | Herbie Hancock | ? | -
Byrd Like | Freddie Hubbard | ? | -
[C]
C'est Si Bon | Henri Betti | ? | medium
Call Me | Tony Hatch | ? | -
Call Me Irresponsible | Jimmy Van Heusen | ? | medium
Can't Help Lovin' Dat Man | Jerome Kern | ? | ballad
Captain Marvel | Chick Corea | ? | -
Central Park West | John Coltrane | ? | ballad
Ceora | ? | ? | -
Chega de Saudade (No More Blues) | Antonio Carlos Jobim | ? | bossa
Chelsea Bells | Steve Swallow | ? | -
Chelsea Bridge | Billy Strayhorn | ? | ballad
Cherokee (Indian Love Song) | Ray Noble | Bb | up
Cherry Pink and Apple Blossom White | Louiguy | ? | latin
A Child Is Born | Thad Jones | ? | ballad
Chippie | Ornette Coleman | ? | up
Chitlins Con Carne | Kenny Burrell | ? | blues
Come Sunday | Duke Ellington | ? | ballad
Como en Vietnam | ? | ? | -
Con Alma | Dizzy Gillespie | ? | ballad
Conception | George Shearing | ? | medium
Confirmation | Charlie Parker | F | up
Contemplation | McCoy Tyner | ? | -
Coral | ? | ? | -
Cotton Tail | Duke Ellington | Bb | up
Could It Be You | ? | ? | -
Countdown | John Coltrane | ? | up
Crescent | John Coltrane | ? | -
Crystal Silence | Chick Corea | ? | ballad
[D]
D Natural Blues | Wes Montgomery | ? | blues
Daahoud | Clifford Brown | ? | up
Dancing on the Ceiling | Richard Rodgers | ? | medium
Darn That Dream | Jimmy Van Heusen | ? | ballad
Day Waves | ? | ? | -
Days and Nights Waiting | ? | ? | -
Dear Old Stockholm | Traditional Swedish folk tune | ? | up
Dearly Beloved | Jerome Kern | ? | ballad
Dedicated to You | ? | ? | -
Deluge | Wayne Shorter | ? | -
Desafinado | Antonio Carlos Jobim | ? | bossa
Desert Air | ? | ? | -
Detour Ahead | Herb Ellis, John Frigo, Lou Carter | ? | ballad
Dexterity | Charlie Parker | ? | up
Dizzy Atmosphere | Dizzy Gillespie | ? | up
Django | John Lewis | ? | ballad
Doin' the Pig | ? | ? | -
Dolores | Wayne Shorter | ? | medium
Dolphin Dance | Herbie Hancock | ? | medium
Domino Biscuit | ? | ? | -
Don't Blame Me | Jimmy McHugh | ? | ballad
Don't Get Around Much Anymore | Duke Ellington | ? | medium
Donna Lee | Charlie Parker | Ab | up
Dream a Little Dream of Me | Fabian Andre, Wilbur Schwandt | ? | medium
Dreamsville | Henry Mancini | ? | ballad
[E]
Easter Parade | Irving Berlin | ? | medium
Easy Living | Ralph Rainger | ? | ballad
Easy to Love (You'd Be So Easy to Love) | Cole Porter | ? | medium
Ecclusiastics | Charles Mingus | ? | -
Eighty One | Ron Carter, Miles Davis | ? | up
El Gaucho | Wayne Shorter | ? | -
Epistrophy | Thelonious Monk, Kenny Clarke | ? | medium
Equinox | John Coltrane | Cm | blues
Equipoise | ? | ? | -
E.S.P. | Wayne Shorter | ? | up
[F]
Fall | Wayne Shorter | ? | -
Falling Grace | Steve Swallow | ? | -
Falling in Love with Love | Richard Rodgers | ? | -
Fee-Fi-Fo-Fum | Wayne Shorter | ? | -
A Fine Romance | Jerome Kern | ? | medium
Follow Your Heart | ? | ? | -
Footprints | Wayne Shorter | Cm | waltz
For All We Know | J. Fred Coots | ? | ballad
For Heaven's Sake | ? | ? | -
(I Love You) For Sentimental Reasons | Deek Watson, William Best | ? | ballad
Forest Flower | Charles Lloyd | ? | -
Four | Miles Davis | Eb | up | voice=trumpet
Four on Six | Wes Montgomery | Gm | up
Freddie Freeloader | Miles Davis | Bb | blues | voice=trumpet
Freedom Jazz Dance | Eddie Harris | ? | funk
Full House | Wes Montgomery | ? | -
[G]
Gee Baby, Ain't I Good to You | Don Redman, Andy Razaf | ? | -
Gemini | ? | ? | -
Giant Steps | John Coltrane | B | up
The Girl from Ipanema (Garôta de Ipanema) | Antonio Carlos Jobim | F | bossa
Gloria's Step | Scott LaFaro | ? | -
God Bless' the Child | Billie Holiday, Arthur Herzog Jr. | ? | ballad
Golden Lady | Stevie Wonder | ? | -
Good Evening Mr. and Mrs. America | ? | ? | -
Grand Central | John Coltrane | ? | up
The Green Mountains | ? | ? | -
Groovin' High | Dizzy Gillespie | ? | up
Grow Your Own | ? | ? | -
Guilty | ? | ? | -
Gypsy in My Soul | ? | ? | -
[H]
Half Nelson | Miles Davis | C | up | voice=trumpet
Have You Met Miss Jones? | Richard Rodgers | F | medium
Heaven | ? | ? | -
Heebie Jeebies | Boyd Atkins | ? | up
Hello, Young Lovers | ? | ? | -
Here's That Rainy Day | Jimmy Van Heusen | ? | ballad
Hot Toddy | ? | ? | -
House of Jade | ? | ? | -
How High the Moon | Morgan Lewis | G | up
How Insensitive (Insensatez) | Antonio Carlos Jobim | Dm | bossa
How My Heart Sings | Earl Zindars | ? | waltz
Hullo Bolinas | Steve Swallow | ? | -
[I]
I Can't Get Started | Vernon Duke | ? | ballad
I Can't Give You Anything but Love | Jimmy McHugh | ? | medium
I Could Write a Book | Richard Rodgers | ? | medium
I Got It Bad and That Ain't Good | Duke Ellington | ? | ballad
I Let a Song Go Out of My Heart | Duke Ellington | ? | medium
I Love Paris | Cole Porter | ? | medium
I Love You | Cole Porter | ? | medium
I Mean You | Thelonious Monk | ? | medium | voice=piano
I Remember Clifford | Benny Golson | ? | ballad
I Should Care | Axel Stordahl, Paul Weston | ? | ballad
I Wish I Knew How It Would Feel to Be Free | Billy Taylor | ? | -
I'll Never Smile Again | Ruth Lowe | ? | ballad
I'll Remember April | Gene de Paul | ? | up
I'm All Smiles | ? | ? | -
I'm Beginning to See the Light | Duke Ellington, Harry James, Don George, Johnny Hodges | ? | medium
I'm Your Pal | ? | ? | -
Icarus | Ralph Towner | ? | -
If You Never Come to Me (Inutil Paisagem) | Antonio Carlos Jobim | ? | bossa
Impressions | John Coltrane | Ddor | modal
In a Mellow Tone | Duke Ellington | ? | medium
In a Sentimental Mood | Duke Ellington | ? | ballad
In the Mood | Joe Garland | ? | medium
In the Wee Small Hours of the Morning | David Mann | ? | ballad
In Your Quiet Place | ? | ? | -
The Inch Worm | Frank Loesser | ? | -
Indian Lady | Freddie Hubbard | ? | -
Inner Urge | Joe Henderson | ? | up
Interplay | ? | ? | -
The Intrepid Fox | Freddie Hubbard | ? | -
Invitation | Bronisław Kaper | ? | medium
Iris | Wayne Shorter | ? | ballad
Is You Is, or Is You Ain't (Ma' Baby) | Louis Jordan, Billy Austin | ? | medium
Isn't It Romantic? | Richard Rodgers | ? | medium
Isotope | Joe Henderson | ? | -
Israel | John Carisi | ? | medium
It Don't Mean a Thing (If It Ain't Got That Swing) | Duke Ellington | ? | medium
It's Easy to Remember | Richard Rodgers | ? | ballad
[J]
Jelly Roll | Charles Mingus | ? | -
Jordu | Duke Jordan | ? | up
Journey to Recife | ? | ? | -
Joy Spring | Clifford Brown | ? | medium
Juju | Wayne Shorter | ? | -
Jump Monk | Charles Mingus | ? | up
June in January | Ralph Rainger, Leo Robin | ? | -
Just One More Chance | Sam Coslow, Arthur Johnston | ? | ballad
[K]
Kelo | J. J. Johnson | ? | up
[L]
Lady Bird | Tadd Dameron | C | medium
Lady Sings the Blues | Billie Holiday, Herbie Nichols | ? | ballad
Lament | J. J. Johnson | ? | ballad
Las Vegas Tango | Gil Evans | ? | -
Lazy Bird | John Coltrane | ? | medium
Lazy River | Hoagy Carmichael, Sidney Arodin | ? | medium
Like Someone in Love | Jimmy Van Heusen | ? | ballad
Limehouse Blues | Philip Braham | ? | up
Lines and Spaces | ? | ? | -
Litha | Chick Corea | ? | up
Little Boat (O Barquinho) | Roberto Menescal | ? | bossa
Little Waltz | Ron Carter | ? | waltz
Long Ago (And Far Away) | Jerome Kern | ? | medium
Lonnie's Lament | John Coltrane | ? | ballad
Look to the Sky | Antonio Carlos Jobim | ? | bossa
Love Is the Sweetest Thing | Ray Noble | ? | ballad
Lucky Southern | ? | ? | -
Lullaby of Birdland | George Shearing | ? | medium
Lush Life | Billy Strayhorn | ? | ballad
[M]
The Magician in You | ? | ? | -
Mahjong | Wayne Shorter | ? | -
Maiden Voyage | Herbie Hancock | ? | modal
A Man and a Woman (Un Homme et Une Femme) | Francis Lai | ? | bossa
Man in the Green Shirt | ? | ? | -
Meditation (Meditação) | Antonio Carlos Jobim | ? | bossa
Memories of Tomorrow | ? | ? | -
Michelle | John Lennon, Paul McCartney | ? | ballad
Midnight Mood | ? | ? | -
Midwestern Nights Dream | ? | ? | -
Milano | John Lewis | ? | -
Minority | Gigi Gryce | ? | up
Miss Ann | Eric Dolphy | ? | -
Missouri Uncompromised | Pat Metheny | ? | -
Mr. P.C. | John Coltrane | Cm | up
Misty | Erroll Garner | Eb | ballad
Miyako | Wayne Shorter | ? | -
Moment's Notice | John Coltrane | ? | up
Mood Indigo | Duke Ellington, Barney Bigard | ? | ballad
Moonchild | ? | ? | -
The Most Beautiful Girl in the World | Richard Rodgers | ? | -
My Buddy | Walter Donaldson | ? | ballad
My Favorite Things | Richard Rodgers | Em | waltz
My Foolish Heart | Victor Young | ? | ballad
My Funny Valentine | Richard Rodgers | Cm | ballad
My One and Only Love | Guy Wood | ? | ballad
My Romance | Richard Rodgers | ? | ballad
My Shining Hour | Harold Arlen | ? | medium
My Ship | Kurt Weill | ? | ballad
My Way | Jacques Revaux, Claude François | ? | ballad
Mysterious Traveller | ? | ? | -
[N]
Naima (Niema) | John Coltrane | ? | ballad
Nardis | Miles Davis | Em | modal | voice=piano
Nefertiti | Wayne Shorter | ? | -
Never Will I Marry | Frank Loesser | ? | ballad
Nica's Dream | Horace Silver | ? | up
Night Dreamer | Wayne Shorter | ? | -
The Night Has a Thousand Eyes | Jerry Brainin | ? | medium
A Night in Tunisia | Dizzy Gillespie | ? | latin
Night Train | Jimmy Forrest | ? | blues
Nobody Knows You When You're Down and Out | Jimmie Cox | ? | blues
Nostalgia in Times Square | Charles Mingus | ? | medium
Nuages | Django Reinhardt | ? | ballad
[O]
(The Old Man From) The Old Country | Nat Adderley | ? | -
Oleo | Sonny Rollins | Bb | up
Oliloqui Valley | Herbie Hancock | ? | -
Once I Loved (Amor em Paz) (Love in Peace) | Antonio Carlos Jobim | ? | bossa
Once in Love with Amy | Frank Loesser | ? | medium
One Finger Snap | Herbie Hancock | ? | up
One Note Samba (Samba de Uma Nota Só) | Antonio Carlos Jobim | ? | bossa
Only Trust Your Heart | ? | ? | -
Orbits | Wayne Shorter | ? | up
Ornithology | Charlie Parker, Benny Harris | G | up
Out of Nowhere | Johnny Green | ? | medium
[P]
Paper Doll | Johnny S. Black | ? | medium
Passion Dance | McCoy Tyner | ? | up
Passion Flower | Billy Strayhorn | ? | ballad
Peace | Horace Silver | ? | ballad
Peggy's Blue Skylight | Charles Mingus | ? | -
Pent Up House | Sonny Rollins | ? | up
Penthouse Serenade | Will Jason, Val Burton | ? | ballad
Peri's Scope | Bill Evans | ? | medium
Pfrancing (No Blues) | Miles Davis | ? | medium
Pinocchio | Wayne Shorter | ? | up
Pithecanthropus Erectus | Charles Mingus | ? | -
Poem for #15 (The Saga of Harrison Crabfeathers) | Steve Kuhn | ? | -
Portsmouth Figurations | ? | ? | -
Prelude to a Kiss | Duke Ellington | ? | ballad
Prince of Darkness | Wayne Shorter | ? | up
P.S. I Love You | Gordon Jenkins | ? | ballad
Pussy Cat Dues | Charles Mingus | ? | blues
[Q]
Quiet Nights of Quiet Stars (Corcovado) | Antonio Carlos Jobim | ? | bossa
Quiet Now | Denny Zeitlin | ? | ballad
[R]
Recorda Me | Joe Henderson | ? | latin
Red Clay | Freddie Hubbard | ? | funk
Reflections | Thelonious Monk | ? | ballad | voice=piano
Reincarnation of a Lovebird | Charles Mingus | ? | ballad
Ring Dem Bells | Duke Ellington | ? | up
Road Song | Wes Montgomery | ? | -
'Round Midnight | Thelonious Monk | Ebm | ballad | voice=piano
Ruby, My Dear | Thelonious Monk | ? | ballad | voice=piano
[S]
Satin Doll | Duke Ellington, Billy Strayhorn | C | medium
Scotch and Soda | Dave Guard | ? | -
Scrapple from the Apple | Charlie Parker | ? | up
Sea Journey | Chick Corea | ? | -
Seven Come Eleven | Charlie Christian, Benny Goodman | ? | up
Seven Steps to Heaven | Miles Davis, Victor Feldman | ? | up
Sidewinder | Lee Morgan | ? | funk
Silver Hollow | Horace Silver | ? | -
Sirabhorn | ? | ? | -
Skating in Central Park | John Lewis | ? | ballad
So Nice (Summer Samba) | Marcos Valle | ? | bossa
So What | Miles Davis | Ddor | modal | voice=trumpet
Solar | Miles Davis | Cm | medium | voice=trumpet
Solitude | Duke Ellington | ? | ballad
Some Day My Prince Will Come | Frank Churchill | Bb | waltz
Some Other Spring | Arthur Herzog Jr., Irene Kitchings | ? | ballad
Some Skunk Funk | Randy Brecker | ? | funk
Somebody Loves Me | George Gershwin | ? | medium
Sometime Ago | Sergio Mihanovich | ? | -
Song for My Father | Horace Silver | Fm | bossa
The Song Is You | Jerome Kern | ? | medium
Sophisticated Lady | Duke Ellington | ? | ballad
The Sorcerer | Herbie Hancock | ? | -
Speak No Evil | Wayne Shorter | ? | medium
The Sphinx | Ornette Coleman | ? | -
Standing on the Corner | Frank Loesser | ? | medium
The Star-Crossed Lovers | Duke Ellington, Billy Strayhorn | ? | ballad
Stella by Starlight | Victor Young | Bb | medium
Steps | Chick Corea | ? | up
Stolen Moments | Oliver Nelson | Cm | blues
Stompin' at the Savoy | Edgar Sampson, Benny Goodman, Chick Webb | ? | up
Straight No Chaser | Thelonious Monk | F | blues | voice=piano
A String of Pearls | Jerry Gray | ? | medium
Stuff | ? | ? | -
Sugar | Stanley Turrentine | ? | -
A Sunday Kind of Love | Louis Prima | ? | ballad
The Surrey with the Fringe on Top | Richard Rodgers | ? | medium
Swedish Pastry | Barney Kessel | ? | -
Sweet Georgia Bright | ? | ? | -
Sweet Henry | ? | ? | -
[T]
Take Five | Paul Desmond | Ebm | odd | voice=sax bpm=176
Take the “A” Train | Billy Strayhorn | C | up
Tame Thy Pen | ? | ? | -
Tell Me a Bedtime Story | Herbie Hancock | ? | -
Thanks for the Memory | Ralph Rainger | ? | ballad
That's Amore (That's Love) | Harry Warren | ? | -
(There Is) No Greater Love | Isham Jones | ? | medium
There Will Never Be Another You | Harry Warren | ? | medium
There'll Be Some Changes Made | W. Benton Overstreet | ? | medium
They Didn't Believe Me | Jerome Kern | ? | -
Think on Me | ? | ? | -
Thou Swell | Richard Rodgers | ? | medium
Three Flowers | McCoy Tyner | ? | -
Time Remembered | Bill Evans | ? | ballad
Tones for Joan's Bones | Chick Corea | ? | -
Topsy | Edgar Battle, Eddie Durham | ? | up
Tour de Force | Dizzy Gillespie | ? | up
Triste | Antonio Carlos Jobim | ? | bossa
Tune Up | Miles Davis | D | up | voice=trumpet
Turn Out the Stars | Bill Evans | ? | ballad
Twisted Blues | ? | ? | -
[U]
Unchain My Heart | Bobby Sharp | ? | medium
Unity Village | Pat Metheny | ? | -
Uniquity Road | ? | ? | -
Up Jumped Spring | Freddie Hubbard | ? | waltz
Upper Manhattan Medical Group (UMMG) | Billy Strayhorn | ? | medium
[V]
Valse Hot | Sonny Rollins | ? | waltz
Very Early | Bill Evans | ? | waltz
Virgo | Wayne Shorter | ? | -
[W]
Wait Till You See Her | Richard Rodgers | ? | ballad
Waltz for Debby | Bill Evans | F | waltz
Wave | Antonio Carlos Jobim | D | bossa
We'll Be Together Again | Carl Fischer | ? | ballad
Well You Needn't (It's Over Now) | Thelonious Monk | F | medium | voice=piano
West Coast Blues | Wes Montgomery | ? | waltz
What Am I Here For? | Duke Ellington | ? | medium
What Was | ? | ? | -
When I Fall in Love | Victor Young | ? | ballad
When Sunny Gets Blue | Marvin Fisher | ? | ballad
When You Wish Upon a Star | Leigh Harline | ? | ballad
Whispering | John Schonberger, Richard Coburn, Vincent Rose | ? | medium
Wild Flower | Wayne Shorter | ? | -
Windows | Chick Corea | ? | up
Witch Hunt | Wayne Shorter | ? | up
Wives and Lovers (Hey, Little Girl) | Burt Bacharach | ? | -
Woodchopper's Ball | Joe Bishop, Woody Herman | ? | up
Woody 'n You | Dizzy Gillespie | ? | up
The World Is Waiting for the Sunrise | Ernest Seitz | ? | medium
[Y]
Yes and No | Wayne Shorter | ? | -
Yesterday | John Lennon, Paul McCartney | ? | ballad
Yesterdays | Jerome Kern | ? | ballad
You Are the Sunshine of My Life | Stevie Wonder | ? | medium
You Are Too Beautiful | Richard Rodgers | ? | ballad
You Brought a New Kind of Love to Me | Sammy Fain | ? | medium
You Don't Know What Love Is | Gene de Paul | ? | ballad
You Took Advantage of Me | Richard Rodgers | ? | medium
You're Nobody 'til Somebody Loves You | Russ Morgan, Larry Stock, James Cavanaugh | ? | medium
Young at Heart | Johnny Richards | ? | ballad
"""
}
