# correlate

The aim of this project was to find out how activities influence my
mental focus.

## Talk

This was the underlying code for my talk at [ClojuTRE][clojutre] 2018
titled *Optimizing My Mental Focus with Machine Learning and Clojure*

 * [Video of the talk on YouTube][clojutre-video]
 * [Presentation slides with short videos][clojutre-slides]

## What?

Mental focus was hard to define, so I tracked "Brain Fog" instead,
basically tracking my mental un-focus. It's easier to ask yourself
"How foggy do I feel today?" instead of "How focussed do I feel
today?", at least for me.

Activities can be anything. In my case, I mainly tracked my food
because I had this theory (the "Kebab Theory") that certain foods made
me feel less focused the next day, especially Kebabs.

## Why?

I saw [this great project][weight-loss] for analyzing weight loss data
with vowpal-wabbit in the hope of finding out which factors influence
weight loss (and weight gain) and tried to apply a similar approach to
mental focus while using Clojure.

## How?

Simple:

 1. Collect data
 2. Parse it
 3. Shove it into [vowpal-wabbit][vowpal-wabbit]
 4. Get results and hope you understand them.

Let's go through these steps in detail:

### 1. Collect data

Here's my [Google Sheets template][spreadsheet-template].

![entering data into Google Sheets](images/google_sheets.gif?raw=true)

I used Google Sheets for collecting data. Put simply, you enter all
relevant data into the spreadsheet. There are two types of data:
**Events** and **Measurements**. Events never have values (no value in
the *value* column) whereas Measurements often do.

Measurements are things which matter to you. In my case, my Brain
Fog. I would grade it on a subjective scale of 0 (no fog, very
focused) to 5 (feeling foggy and unfocused). Further, I would do a
[Stroop Test][stroop-effect] every morning which yields some numerical
values (time in seconds to complete the test) which I would also enter
in the *value* column.

Check out the template (see above) for some example values.

Protip: <kbd>⌘</kbd>+<kbd>Option</kbd>+<kbd>Shift</kbd>+<kbd>;</kbd>
(on macOS) inserts the current datetime into Google Sheets (select a
cell first). Check out Help --> Keyboard Shortcuts and search for
"date" if you're on another operating system.

### 2., 3. and 4.: Check out the tutorial!

I created a [tutorial][tutorial-file] located in
`src/correlate/tutorial.clj`!

**Important! Do these things first:**

Install Vowpal-Wabbit (here on macOS, check [the site][vowpal-wabbit]
for more info for your platform):

``` shell
brew install vowpal-wabbit
```

Clone this repository:

``` shell
git clone https://github.com/olieidel/correlate.git
```

Go to directory and install dependencies:

``` shell
cd correlate
lein deps
```

Finally, fire up your REPL of choice and navigate to
`src/correlate/tutorial.clj` :)

## Random

The code I presented at my talk can be found in the file
`src/correlate/clojutre_talk.clj`.

Feel free to reach out to me if things are unclear! :)

## License

Copyright © 2018 Oliver Eidel

Licensed under the [MIT License](LICENSE.md).

[clojutre]: https://clojutre.org/2018/
[clojutre-video]: https://www.youtube.com/watch?v=jpFveXUe65I
[clojutre-slides]: https://drive.google.com/open?id=1jocY8plr42JTO_5gIQwIcQQkCbBOPcBV9nz7BmttGcI
[weight-loss]: https://github.com/arielf/weight-loss
[vowpal-wabbit]: https://github.com/JohnLangford/vowpal_wabbit/wiki
[spreadsheet-template]: https://docs.google.com/spreadsheets/d/15yqZN8x3E-xXO2mqDMQ72gv6JqoRGaEWVktMRDTgWSQ/edit?usp=sharing
[stroop-effect]: https://en.wikipedia.org/wiki/Stroop_effect
